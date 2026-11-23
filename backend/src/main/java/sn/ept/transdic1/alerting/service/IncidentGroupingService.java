package sn.ept.transdic1.alerting.service;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.Incident;
import sn.ept.transdic1.alerting.domain.IncidentAlert;
import sn.ept.transdic1.alerting.domain.IncidentState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.IncidentAlertRepository;
import sn.ept.transdic1.alerting.repository.IncidentRepository;
import sn.ept.transdic1.common.alerting.IncidentGrouping;

/**
 * Regroupement des alertes en incidents (M10 du plan, ADR 0039) : <b>un fait, une notification</b>.
 *
 * <p>Jusqu'ici, une panne en amont qui fait tomber cinq machines ouvrait cinq alertes et réveillait
 * cinq fois. Le regroupement de l'ADR 0012 ne savait le faire que pour les pertes de collecte, en
 * suivant la topologie ; les anomalies, les seuils, les événements SNMP, les prévisions et les
 * alertes de sécurité restaient chacun de leur côté.
 *
 * <p><b>Les règles, dans cet ordre.</b> Toutes exigent que l'alerte arrive dans la fenêtre de
 * l'incident ({@code window}, dix minutes par défaut, comptée depuis sa dernière alerte).
 *
 * <ol>
 *   <li><b>Même équipement</b> : la machine a déjà une alerte dans un incident ouvert. Trois
 *       alertes sur le même serveur en dix minutes, c'est un fait, pas trois.
 *   <li><b>Topologie</b> : l'équipement est à {@code maxDistance} liens ou moins d'un équipement de
 *       l'incident, dans un sens ou dans l'autre. C'est le signal le plus fort après le précédent,
 *       parce qu'il dit <i>par quoi</i> les deux alertes peuvent être liées.
 *   <li><b>Même site et même nature</b> : même site, et soit la même règle d'alerte, soit des
 *       messages de gabarit quasi identique ({@link MessageSimilarity}). Sans la condition de
 *       nature, tout ce qui arrive en même temps sur un site finirait dans le même sac. La
 *       ressemblance des messages est le parent pauvre de ces trois règles, et l'ADR dit pourquoi :
 *       mesurée sur les messages réels de la plateforme, elle ne sépare pas « même fait » de «
 *       faits différents ». Elle ne sert qu'au cas du gabarit identique — la même alerte sur
 *       plusieurs machines d'un site —, parce que l'alerte ne porte pas de clé d'indicateur.
 * </ol>
 *
 * <p>Chaque rattachement porte sa raison en clair. Un regroupement que l'opérateur ne peut pas
 * vérifier, il ne lui fera pas confiance — et il aura raison : la fenêtre de temps rapproche aussi
 * des faits qui n'ont rien à voir.
 *
 * <p><b>Pourquoi deux écouteurs.</b> La notification part de {@link AlertChangedEvent} ; le
 * rattachement doit donc être fait <i>avant</i> que la file des notifications ne le traite, d'où
 * l'ordre le plus prioritaire sur cet événement. Mais une alerte sous maintenance, ou déjà
 * regroupée par l'ADR 0012, ne publie jamais cet événement : elle n'arriverait dans aucun incident
 * et manquerait à l'écran. {@link AlertActivityEvent}, lui, est publié dans tous les cas, et sert
 * de filet — sans jamais donner la parole, puisque personne n'est prévenu pour cette alerte-là.
 */
@Service
public class IncidentGroupingService implements IncidentGrouping {
  private static final Logger LOG = LoggerFactory.getLogger(IncidentGroupingService.class);

  /** Au-delà, un titre d'incident ne tient plus dans la colonne ni dans un écran. */
  private static final int TITLE_LENGTH = 300;

  private final JdbcTemplate jdbc;
  private final EntityManager em;
  private final IncidentRepository incidents;
  private final IncidentAlertRepository links;
  private final MeterRegistry meters;
  private final Clock clock;
  private final boolean enabled;
  private final Duration window;
  private final int maxDistance;
  private final double minSimilarity;

  public IncidentGroupingService(
      JdbcTemplate jdbc,
      EntityManager em,
      IncidentRepository incidents,
      IncidentAlertRepository links,
      MeterRegistry meters,
      Clock clock,
      @Value("${alerting.incident.enabled:true}") boolean enabled,
      @Value("${alerting.incident.window-minutes:10}") long windowMinutes,
      @Value("${alerting.incident.max-distance:2}") int maxDistance,
      @Value("${alerting.incident.min-similarity:0.6}") double minSimilarity) {
    this.jdbc = jdbc;
    this.em = em;
    this.incidents = incidents;
    this.links = links;
    this.meters = meters;
    this.clock = clock;
    this.enabled = enabled;
    this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
    // Au-delà de quatre liens, « voisin dans la topologie » ne veut plus rien dire sur un campus.
    this.maxDistance = Math.min(4, Math.max(1, maxDistance));
    this.minSimilarity = Math.min(1.0, Math.max(0.3, minSimilarity));
  }

  /** Les faits d'une alerte qui servent au regroupement. */
  record Facts(
      UUID alertId,
      UUID equipmentId,
      String equipmentName,
      UUID siteId,
      String siteName,
      String type,
      UUID ruleId,
      String message,
      Severity severity) {}

  /** Un rattachement décidé : l'incident, la raison, et si cette alerte prend la parole. */
  public record Attachment(UUID incidentId, String reason, boolean announces, boolean created) {}

  /**
   * Avant la file des notifications, qui écoute le même événement : l'alerte doit être rangée dans
   * son incident pour que {@link #announces} puisse répondre.
   */
  @EventListener
  @Order(Ordered.HIGHEST_PRECEDENCE)
  @Transactional
  public void onAlertChanged(AlertChangedEvent event) {
    if (event.type() == AlertEventType.OPENED) {
      attach(event.alertId(), clock.instant(), true);
    }
  }

  /**
   * Filet : les alertes qui ne notifient pas (maintenance, regroupement de l'ADR 0012) ne publient
   * pas {@link AlertChangedEvent} et seraient absentes des incidents. Elles entrent ici, sans
   * prendre la parole. La résolution passe aussi par là, parce qu'elle doit être vue même quand
   * personne n'est prévenu.
   */
  @EventListener
  @Transactional
  public void onAlertActivity(AlertActivityEvent event) {
    Instant now = clock.instant();
    switch (event.type()) {
      case OPENED -> attach(event.alertId(), now, false);
      case RESOLVED, ARCHIVED, DELETED -> settle(event.alertId(), now);
      default -> {
        // Prise en charge, commentaire, notification : rien à décider pour le regroupement.
      }
    }
  }

  @Override
  @Transactional(readOnly = true)
  public boolean announces(UUID alertId) {
    // Une alerte qu'aucun incident n'a rangée notifie : le regroupement ne doit jamais faire taire
    // ce qu'il n'a pas compris.
    return links.findByAlertId(alertId).map(IncidentAlert::isAnnounces).orElse(true);
  }

  /**
   * Range une alerte dans un incident, ou en ouvre un.
   *
   * @param mayAnnounce vrai si quelqu'un est sur le point d'être prévenu pour cette alerte ; faux
   *     pour une alerte muette, qui ne doit pas consommer la parole de l'incident
   * @return le rattachement, ou vide si le regroupement est désactivé ou l'alerte introuvable
   */
  @Transactional
  public Optional<Attachment> attach(UUID alertId, Instant now, boolean mayAnnounce) {
    if (!enabled) {
      return Optional.empty();
    }
    // L'alerte vient d'être enregistrée par le détecteur, qui a publié son événement sans vider
    // la session : JPA la garde en attente, et les requêtes SQL ci-dessous ne la verraient pas.
    // Constaté sur l'instance de démonstration — dix alertes ouvertes, aucun incident formé, et
    // aucun test ne l'avait vu parce qu'ils vident tous la session avant d'appeler. L'écriture est
    // de toute façon due au commit : la provoquer ici ne fait que la rendre lisible.
    em.flush();
    // Idempotent : les deux écouteurs passent par ici pour la même alerte.
    Optional<IncidentAlert> existing = links.findByAlertId(alertId);
    if (existing.isPresent()) {
      IncidentAlert link = existing.get();
      return Optional.of(
          new Attachment(link.getIncidentId(), link.getReason(), link.isAnnounces(), false));
    }
    Facts facts = facts(alertId);
    if (facts == null) {
      return Optional.empty();
    }
    Optional<Match> match = bestMatch(facts, now);
    Incident incident =
        match.map(found -> found.incident()).orElseGet(() -> incidents.save(open(facts, now)));
    boolean created = match.isEmpty();
    String reason = match.map(Match::reason).orElse("Première alerte de l'incident");

    if (!created) {
      incident.setLastAlertAt(now);
      if (facts.severity().ordinal() > incident.getSeverity().ordinal()) {
        incident.setSeverity(facts.severity());
      }
      incidents.save(incident);
    }
    boolean announces = mayAnnounce && claim(incident, facts.severity());
    links.save(new IncidentAlert(incident.getId(), alertId, now, reason, announces));
    if (!created) {
      // Chaque alerte rangée sans prendre la parole est une notification évitée : le compteur
      // mesure la réduction du bruit, comme pour le regroupement de l'ADR 0012.
      meters.counter("alerting.incident.grouped").increment();
      if (mayAnnounce && !announces) {
        meters.counter("alerting.incident.silenced").increment();
      }
      LOG.debug("Alerte {} rangée dans l'incident {} : {}", alertId, incident.getId(), reason);
    }
    return Optional.of(new Attachment(incident.getId(), reason, announces, created));
  }

  /**
   * L'incident donne-t-il la parole à cette alerte ? Oui si rien n'a encore été notifié, oui aussi
   * si elle est plus grave que ce qui l'a été : un incident notifié en avertissement qui devient
   * critique doit réveiller quelqu'un une seconde fois.
   */
  private boolean claim(Incident incident, Severity severity) {
    Severity notified = incident.getNotifiedSeverity();
    if (notified != null && severity.ordinal() <= notified.ordinal()) {
      return false;
    }
    incident.setNotifiedSeverity(severity);
    incidents.save(incident);
    return true;
  }

  private Incident open(Facts facts, Instant now) {
    return new Incident(facts.siteId(), title(facts), facts.severity(), now);
  }

  /** Titre : le message de l'alerte qui a commencé l'incident, tronqué. */
  static String title(Facts facts) {
    String message = facts.message() == null ? "Incident" : facts.message();
    return message.length() <= TITLE_LENGTH
        ? message
        : message.substring(0, TITLE_LENGTH - 1) + "…";
  }

  /** Un incident candidat et la raison pour laquelle l'alerte lui revient. */
  private record Match(Incident incident, int rank, String reason) {}

  /**
   * Cherche l'incident ouvert auquel cette alerte revient. Les règles sont examinées dans l'ordre,
   * et à règle égale l'incident le plus récent gagne : c'est celui dont le fait est encore en train
   * de se produire.
   */
  private Optional<Match> bestMatch(Facts facts, Instant now) {
    List<Incident> candidates =
        incidents.findByStateAndLastAlertAtGreaterThanEqual(IncidentState.OPEN, now.minus(window));
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    Map<UUID, Integer> near = depths(facts.equipmentId());
    var matches = new ArrayList<Match>();
    for (Incident candidate : candidates) {
      members(candidate.getId()).stream()
          .map(member -> judge(facts, member, near))
          .filter(java.util.Objects::nonNull)
          .min(Comparator.comparingInt(Verdict::rank))
          .ifPresent(best -> matches.add(new Match(candidate, best.rank(), best.reason())));
    }
    return matches.stream()
        .min(
            Comparator.comparingInt(Match::rank)
                .thenComparing(
                    match -> match.incident().getLastAlertAt(), Comparator.reverseOrder()));
  }

  /** Un équipement déjà présent dans un incident, avec ce qu'il faut pour juger. */
  private record Member(
      UUID equipmentId,
      String equipmentName,
      UUID siteId,
      String type,
      UUID ruleId,
      String message) {}

  /** Le rang de la règle qui a joué, et sa raison en clair. Rang 1 = règle la plus forte. */
  private record Verdict(int rank, String reason) {}

  /** La règle qui rapproche l'alerte d'une alerte déjà rangée, ou null si aucune ne le fait. */
  private Verdict judge(Facts facts, Member member, Map<UUID, Integer> near) {
    if (member.equipmentId().equals(facts.equipmentId())) {
      return new Verdict(1, "Même équipement : " + facts.equipmentName());
    }
    Integer distance = near.get(member.equipmentId());
    if (distance != null) {
      return new Verdict(
          2,
          "Voisin dans la topologie : "
              + member.equipmentName()
              + (distance == 1 ? " (lien direct)" : " (" + distance + " liens)"));
    }
    if (facts.siteId() == null || !facts.siteId().equals(member.siteId())) {
      return null;
    }
    // La même règle d'alerte qui parle de deux machines d'un même site : signal exact, lu dans le
    // schéma et non deviné. L'alerte ne porte pas de clé d'indicateur — pour les détecteurs sans
    // règle, c'est la ressemblance des messages qui tient ce rôle, faute de mieux.
    if (facts.ruleId() != null && facts.ruleId().equals(member.ruleId())) {
      return new Verdict(3, "Même règle d'alerte sur le même site : " + member.equipmentName());
    }
    double similarity =
        MessageSimilarity.between(
            facts.message(),
            member.message(),
            facts.equipmentName(),
            member.equipmentName(),
            facts.siteName());
    if (similarity >= minSimilarity) {
      return new Verdict(
          3,
          "Même site et alerte semblable : "
              + member.equipmentName()
              + " ("
              + Math.round(similarity * 100)
              + " % de mots communs)");
    }
    return null;
  }

  /**
   * Profondeur de chaque équipement atteignable depuis celui-ci. Le parcours est <b>non orienté</b>
   * : un amont en panne explique son aval, mais un aval en panne peut tout aussi bien révéler le
   * lien lui-même, et la topologie de l'EPT n'est pas un arbre parfait.
   */
  private Map<UUID, Integer> depths(UUID equipmentId) {
    var depths = new HashMap<UUID, Integer>();
    jdbc.query(
        "WITH RECURSIVE voisinage(id, depth) AS ("
            + " SELECT CAST(? AS uuid), 0"
            + " UNION"
            + " SELECT CASE WHEN l.equipment_id = v.id THEN l.upstream_id ELSE l.equipment_id END,"
            + " v.depth + 1 FROM equipment_link l JOIN voisinage v"
            + " ON l.equipment_id = v.id OR l.upstream_id = v.id WHERE v.depth < ?)"
            + " SELECT id, min(depth) FROM voisinage WHERE depth > 0 GROUP BY id",
        rs -> {
          depths.put(rs.getObject(1, UUID.class), rs.getInt(2));
        },
        equipmentId,
        maxDistance);
    return depths;
  }

  /** Équipements et alertes déjà rangés dans un incident. */
  private List<Member> members(UUID incidentId) {
    return jdbc.query(
        "SELECT a.equipment_id, e.name, e.site_id, a.type, a.rule_id, a.message"
            + " FROM incident_alert ia JOIN alert a ON a.id = ia.alert_id"
            + " JOIN equipment e ON e.id = a.equipment_id WHERE ia.incident_id = ?",
        (rs, i) ->
            new Member(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getObject(5, UUID.class),
                rs.getString(6)),
        incidentId);
  }

  private Facts facts(UUID alertId) {
    List<Facts> found =
        jdbc.query(
            "SELECT a.id, a.equipment_id, e.name, e.site_id, s.name, a.type, a.rule_id,"
                + " a.message, a.severity FROM alert a JOIN equipment e ON e.id = a.equipment_id"
                + " LEFT JOIN site s ON s.id = e.site_id WHERE a.id = ?",
            (rs, i) ->
                new Facts(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getObject(4, UUID.class),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getObject(7, UUID.class),
                    rs.getString(8),
                    Severity.valueOf(rs.getString(9))),
            alertId);
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * Une alerte vient de retomber : si plus aucune alerte de son incident n'est active, l'incident
   * est refermé. Il n'y a pas de résolution « à la main » d'un incident — ce sont les alertes qui
   * portent le travail, et deux cycles de vie concurrents créeraient deux vérités.
   */
  @Transactional
  public void settle(UUID alertId, Instant now) {
    if (!enabled) {
      return;
    }
    flushBeforeReading();
    links
        .findByAlertId(alertId)
        .flatMap(link -> incidents.findById(link.getIncidentId()))
        .filter(incident -> incident.getState() == IncidentState.OPEN)
        .ifPresent(
            incident -> {
              Integer active =
                  jdbc.queryForObject(
                      "SELECT count(*) FROM incident_alert ia JOIN alert a ON a.id = ia.alert_id"
                          + " WHERE ia.incident_id = ? AND a.state IN ('OPEN', 'IN_PROGRESS')",
                      Integer.class,
                      incident.getId());
              if (active != null && active == 0) {
                incident.resolve(now);
                incidents.save(incident);
              }
            });
  }

  /** Vide la session avant de relire en SQL, pour la même raison que dans {@link #attach}. */
  private void flushBeforeReading() {
    em.flush();
  }

  /** Nombre d'alertes d'un incident, pour les écrans. */
  @Transactional(readOnly = true)
  public long size(UUID incidentId) {
    return links.countByIncidentId(incidentId);
  }

  /** Équipements distincts touchés par un incident, pour les écrans. */
  @Transactional(readOnly = true)
  public Set<UUID> equipments(UUID incidentId) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT DISTINCT a.equipment_id FROM incident_alert ia"
                + " JOIN alert a ON a.id = ia.alert_id WHERE ia.incident_id = ?",
            UUID.class,
            incidentId));
  }
}
