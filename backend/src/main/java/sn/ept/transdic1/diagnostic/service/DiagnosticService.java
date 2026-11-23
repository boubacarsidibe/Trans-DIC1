package sn.ept.transdic1.diagnostic.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.dto.CapacityForecastResponse;
import sn.ept.transdic1.alerting.service.CapacityForecastService;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.diagnostic.domain.MetricLink;
import sn.ept.transdic1.diagnostic.domain.Relation;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.AlerteVoisine;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.CauseProbable;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.ChangementRecent;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.ContexteMetrique;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.EquipementTouche;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.EvenementRecent;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.Impact;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.IncidentsSimilaires;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.MetriqueLiee;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.Resolution;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.ServiceTouche;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.Valeur;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRepository;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRepository.SimilarKey;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.AlertFacts;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.ChangeRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.EventRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.MetricValue;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.Page;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.Reference;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.SimilarStats;

/**
 * Assistant de diagnostic d'incident (F2.1, ADR 0024) : explique une alerte au lieu de la lister.
 * Le diagnostic est calculé à la demande, de façon déterministe, à partir des seules données en
 * base (topologie, alertes, événements réseau, audit, mesures, prévisions) ; aucun appel réseau,
 * aucun modèle externe. Chaque cause porte ses preuves ; le résumé n'assemble que des faits de la
 * réponse.
 *
 * <p>Périmètre (ADR 0018) : l'alerte hors périmètre répond 404 comme sa lecture ; ses voisins,
 * événements, changements et équipements touchés hors périmètre sont filtrés dans les requêtes.
 *
 * <p>Coût : une quinzaine de requêtes bornées par diagnostic, indépendamment du volume ; cible p95
 * &lt; 300 ms sur la base de démonstration.
 */
@Service
public class DiagnosticService {
  private static final Logger LOG = LoggerFactory.getLogger(DiagnosticService.class);

  /** Taille des listes affichées (équipements, services, voisins, événements, changements). */
  static final int DISPLAY_LIMIT = 10;

  /**
   * Plafond de points lus pour les corrélations : deux heures à la minute sur une poignée
   * d'équipements et une vingtaine d'indicateurs tiennent largement dedans, et une collecte
   * emballée ne peut pas faire exploser la requête.
   */
  static final int MAX_SERIES_POINTS = 20_000;

  /** Avant/pendant de movedMetrics : le « pendant » commence un quart d'heure avant l'ouverture. */
  private static final Duration MOVED_SPLIT = Duration.ofMinutes(15);

  /** Lignes lues pour la détection des causes (événements, changements, alertes liées). */
  static final int FETCH_LIMIT = 50;

  /** Sites affichés au plus. */
  static final int SITE_LIMIT = 20;

  /** Mesures affichées dans le contexte. */
  static final int LAST_VALUES = 10;

  /** Jours de référence pour la moyenne à la même heure, et minimum pour la donner. */
  static final int REFERENCE_DAYS = 7;

  static final int MIN_REFERENCE_DAYS = 3;

  /** Horizon des incidents similaires, et résolutions montrées. */
  static final int SIMILAR_DAYS = 90;

  static final int LAST_RESOLUTIONS = 3;

  /** Pistes proposées : entre deux et cinq. */
  static final int MAX_STEPS = 5;

  private static final Map<String, String> OPERATORS =
      Map.of(
          "GREATER_THAN", ">",
          "GREATER_OR_EQUAL", "≥",
          "LESS_THAN", "<",
          "LESS_OR_EQUAL", "≤",
          "EQUAL", "=");

  private final DiagnosticRepository repository;
  private final CapacityForecastService forecasts;
  private final SiteScope scope;
  private final Clock clock;
  private final List<NextStepProvider> steps;
  private final Duration window;
  private final ZoneId zone;
  private final Duration correlationWindow;
  private final LinkedMetrics.Settings correlationSettings;
  private final int maxNeighbours;

  public DiagnosticService(
      DiagnosticRepository repository,
      CapacityForecastService forecasts,
      SiteScope scope,
      Clock clock,
      List<NextStepProvider> steps,
      @Value("${diagnostic.window-minutes:30}") long windowMinutes,
      @Value("${diagnostic.time-zone:Africa/Dakar}") String zone,
      @Value("${diagnostic.correlation.window-minutes:120}") long correlationWindowMinutes,
      @Value("${diagnostic.correlation.min-points:10}") int minPoints,
      @Value("${diagnostic.correlation.min-correlation:0.7}") double minCorrelation,
      @Value("${diagnostic.correlation.alpha:0.05}") double alpha,
      @Value("${diagnostic.correlation.max-neighbours:6}") int maxNeighbours,
      @Value("${diagnostic.correlation.max-gap-minutes:5}") long maxGapMinutes) {
    this.repository = repository;
    this.forecasts = forecasts;
    this.scope = scope;
    this.clock = clock;
    this.steps = List.copyOf(steps);
    this.window = Duration.ofMinutes(Math.max(1, Math.min(windowMinutes, 24 * 60)));
    this.zone = ZoneId.of(zone);
    this.correlationWindow =
        Duration.ofMinutes(Math.max(30, Math.min(correlationWindowMinutes, 24 * 60)));
    this.correlationSettings =
        new LinkedMetrics.Settings(
            // En dessous de dix instants communs, une correlation est une coincidence.
            Math.max(10, minPoints),
            // Et en dessous de 0,5, elle est vraie mais illisible : l'operateur ne saurait
            // qu'en faire.
            Math.min(0.99, Math.max(0.5, minCorrelation)),
            Math.min(0.1, Math.max(0.001, alpha)),
            DISPLAY_LIMIT / 2,
            Duration.ofMinutes(Math.max(1, maxGapMinutes)));
    this.maxNeighbours = Math.min(20, Math.max(0, maxNeighbours));
  }

  @Transactional(readOnly = true)
  public DiagnosticResponse diagnose(UUID alertId) {
    AlertFacts alert =
        repository
            .alert(alertId)
            .filter(a -> scope.allows(a.siteId()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable"));
    Optional<Set<UUID>> visible = scope.restriction();
    Instant now = clock.instant();
    Instant opened = alert.openedAt();
    Instant from = opened.minus(window);
    Instant to = opened.plus(window);
    var texts = new DiagnosticTexts(zone, now);

    Impact impact = impact(alert, visible);
    List<AlerteVoisine> neighbours =
        repository.neighbours(alert, from, to, visible, DISPLAY_LIMIT).stream()
            .map(
                n ->
                    new AlerteVoisine(
                        n.id(),
                        n.equipmentId(),
                        DiagnosticTexts.clean(n.equipmentName(), 120),
                        AlertType.valueOf(n.type()),
                        Severity.valueOf(n.severity()),
                        AlertState.valueOf(n.state()),
                        DiagnosticTexts.clean(n.message(), 300),
                        n.triggeredAt(),
                        Relation.values()[n.relation()]))
            .toList();
    List<EventRow> events = repository.events(alert.equipmentId(), from, to, visible, FETCH_LIMIT);
    List<ChangeRow> changes =
        repository
            .changes(alert, from, to, CauseScoring.CHANGE_STRENGTH.keySet(), FETCH_LIMIT)
            .stream()
            // Un équipement en amont sur un site hors périmètre : son changement est caché.
            .filter(c -> !c.upstreamEquipment() || scope.allows(c.targetSiteId()))
            .toList();

    String metricKey =
        MetricKeys.of(alert.type(), alert.occurrenceKey(), alert.equipmentId(), alert.metricType());
    CapacityForecastResponse forecast =
        "FORECAST".equals(alert.type()) ? forecast(alert.equipmentId(), metricKey) : null;
    ContexteMetrique context = metricContext(alert, metricKey, forecast, now);

    var evidence =
        new CauseFinder.Evidence(
            repository.upstreamAlerts(alert.equipmentId(), opened, to, visible, FETCH_LIMIT),
            repository.sameEquipmentAlerts(alert.equipmentId(), alert.id(), to, from, FETCH_LIMIT),
            events,
            changes,
            metricKey,
            forecast);
    List<Scored> ranked =
        CauseScoring.rank(new CauseFinder(alert, texts, window).find(evidence), opened);
    IncidentsSimilaires similar = similar(alert, now);
    LinkedSection linked = linkedMetrics(alert, metricKey);

    return new DiagnosticResponse(
        alert.id(),
        now,
        opened,
        (int) window.toMinutes(),
        resume(alert, impact, ranked, context, similar, texts),
        impact,
        ranked.stream().map(DiagnosticService::cause).toList(),
        neighbours,
        events.stream().limit(DISPLAY_LIMIT).map(DiagnosticService::event).toList(),
        changes.stream().limit(DISPLAY_LIMIT).map(DiagnosticService::change).toList(),
        context,
        linked.metrics(),
        linked.tested(),
        similar,
        nextSteps(alert, metricKey, ranked));
  }

  /** La rubrique des indicateurs lies, et le nombre de series eprouvees pour l'obtenir. */
  private record LinkedSection(List<MetriqueLiee> metrics, int tested) {}

  /** Un indicateur et son changement, le temps du classement. */
  private record Moved(DiagnosticRepository.MovedMetric row, double change) {}

  /**
   * Indicateurs liés à l'alerte, en deux familles réunies dans une seule liste.
   *
   * <p>D'abord ceux qui <b>varient comme</b> la mesure en alerte (M11, ADR 0040) : corrélation de
   * rang sur l'équipement et ses voisins directs. Puis ceux qui ont seulement <b>changé de
   * niveau</b> à l'ouverture, ce que la plateforme montrait déjà. Les premiers passent devant parce
   * qu'ils en disent plus : une marche d'escalier au même moment peut être une coïncidence, tandis
   * que suivre la même courbe pendant deux heures en est rarement une.
   *
   * <p>Ni l'un ni l'autre n'est une preuve de cause, et le diagnostic ne s'en sert pas pour classer
   * ses causes probables : l'écran le dit.
   */
  private LinkedSection linkedMetrics(AlertFacts alert, String metricKey) {
    var byKey = new LinkedHashMap<String, MetriqueLiee>();
    LinkedMetrics.Outcome correlated = correlated(alert, metricKey);
    for (LinkedMetrics.Linked link : correlated.linked()) {
      boolean own = link.equipmentId().equals(alert.equipmentId());
      byKey.put(
          (own ? "" : link.equipmentId() + ":") + link.key(),
          new MetriqueLiee(
              link.key(),
              link.unit(),
              link.before(),
              link.during(),
              orZero(MetricMovement.changePercent(link.before(), link.during())),
              own ? null : link.equipmentName(),
              link.correlation(),
              MetricLink.CORRELATION));
    }
    var moved = new ArrayList<Moved>();
    for (var row : repository.movedMetrics(alert.equipmentId(), alert.openedAt(), metricKey)) {
      Double change = MetricMovement.changePercent(row.before(), row.during());
      if (MetricMovement.isNotable(change, row.pointsBefore(), row.pointsDuring())) {
        moved.add(new Moved(row, change));
      }
    }
    moved.sort(Comparator.comparingDouble((Moved m) -> Math.abs(m.change())).reversed());
    for (Moved m : moved) {
      MetriqueLiee already = byKey.get(m.row().key());
      if (already != null) {
        // Déjà retenu pour sa corrélation : on le dit, plutôt que de l'afficher deux fois.
        byKey.put(
            m.row().key(),
            new MetriqueLiee(
                already.metrique(),
                already.unite(),
                already.avant(),
                already.pendant(),
                m.change(),
                already.equipement(),
                already.correlation(),
                MetricLink.LES_DEUX));
      } else if (byKey.size() < DISPLAY_LIMIT) {
        byKey.put(
            m.row().key(),
            new MetriqueLiee(
                m.row().key(),
                m.row().unit(),
                MetricMovement.round(m.row().before()),
                MetricMovement.round(m.row().during()),
                m.change(),
                null,
                null,
                MetricLink.CHANGEMENT));
      }
    }
    return new LinkedSection(List.copyOf(byKey.values()), correlated.tested());
  }

  /** Corrélations de rang avec la mesure en alerte ; vide si l'alerte ne porte aucun indicateur. */
  private LinkedMetrics.Outcome correlated(AlertFacts alert, String metricKey) {
    if (metricKey == null) {
      // Perte de collecte, événement SNMP : il n'y a pas de série de référence à suivre.
      return LinkedMetrics.Outcome.empty();
    }
    Instant opened = alert.openedAt();
    var points =
        repository.series(
            alert.equipmentId(),
            opened.minus(correlationWindow),
            opened.plus(window),
            maxNeighbours,
            MAX_SERIES_POINTS);
    return LinkedMetrics.correlate(
        points,
        alert.equipmentId(),
        metricKey,
        // Les mêmes bornes que movedMetrics, pour que les deux familles d'indicateurs soient
        // comparables dans la liste commune.
        new LinkedMetrics.Windows(opened.minus(MOVED_SPLIT), opened.plus(Duration.ofMinutes(5))),
        correlationSettings);
  }

  private static double orZero(Double value) {
    return value == null ? 0 : value;
  }

  // ------------------------------------------------------------------ impact

  private Impact impact(AlertFacts alert, Optional<Set<UUID>> visible) {
    Page<sn.ept.transdic1.diagnostic.repository.DiagnosticRows.Node> down =
        repository.downstream(alert.equipmentId(), visible, DISPLAY_LIMIT);
    var services = repository.services(alert.equipmentId(), visible, DISPLAY_LIMIT);
    boolean root = alert.parentAlertId() == null;
    boolean parentVisible = !root && scope.allows(alert.parentSiteId());
    return new Impact(
        root,
        parentVisible ? alert.parentAlertId() : null,
        root
            ? null
            : parentVisible
                ? DiagnosticTexts.clean(alert.correlationReason(), 255)
                : "Rattachée à une alerte racine hors de votre périmètre",
        repository.groupedChildren(alert.id(), visible),
        down.total(),
        down.items().stream()
            .map(
                n ->
                    new EquipementTouche(
                        n.id(),
                        DiagnosticTexts.clean(n.name(), 120),
                        n.type(),
                        DiagnosticTexts.clean(n.siteName(), 120),
                        n.depth(),
                        n.inAlert()))
            .toList(),
        services.total(),
        services.items().stream()
            .map(
                s ->
                    new ServiceTouche(
                        s.id(),
                        DiagnosticTexts.clean(s.name(), 120),
                        s.equipmentId(),
                        DiagnosticTexts.clean(s.equipmentName(), 120)))
            .toList(),
        repository.sites(alert.equipmentId(), visible, SITE_LIMIT).stream()
            .map(name -> DiagnosticTexts.clean(name, 120))
            .toList());
  }

  // ------------------------------------------------------------------ contexte métrique

  private ContexteMetrique metricContext(
      AlertFacts alert, String key, CapacityForecastResponse forecast, Instant now) {
    if (key == null) {
      return null;
    }
    Instant until = alert.resolvedAt() == null ? now : alert.resolvedAt();
    List<MetricValue> last =
        repository.lastValues(alert.equipmentId(), key, alert.serviceId(), until, LAST_VALUES);
    List<MetricValue> atOpening =
        repository.lastValues(alert.equipmentId(), key, alert.serviceId(), alert.openedAt(), 1);
    Double openingValue = atOpening.isEmpty() ? null : atOpening.get(0).value();
    Instant hourStart = alert.openedAt().truncatedTo(ChronoUnit.HOURS);
    int hourUtc = hourStart.atZone(java.time.ZoneOffset.UTC).getHour();
    Reference reference =
        repository.reference(
            alert.equipmentId(),
            key,
            alert.serviceId(),
            hourStart.minus(Duration.ofDays(REFERENCE_DAYS)),
            hourStart,
            hourUtc);
    Double average =
        reference.days() >= MIN_REFERENCE_DAYS && reference.average() != null
            ? round(reference.average(), 2)
            : null;
    Double deviation =
        average != null && openingValue != null && average != 0.0
            ? round((openingValue - average) / Math.abs(average) * 100.0, 1)
            : null;
    String unit = alert.unit() != null ? alert.unit() : last.isEmpty() ? null : last.get(0).unit();
    return new ContexteMetrique(
        key,
        unit,
        last.stream().map(v -> new Valeur(v.at(), v.value())).toList(),
        openingValue,
        average,
        reference.days(),
        deviation,
        alert.threshold(),
        alert.operator(),
        forecast);
  }

  private CapacityForecastResponse forecast(UUID equipmentId, String key) {
    if (key == null) {
      return null;
    }
    try {
      return forecasts.forecasts(equipmentId).stream()
          .filter(f -> key.equals(f.key()))
          .findFirst()
          .orElse(null);
    } catch (RuntimeException e) {
      // La prévision est un complément : son échec ne doit pas priver l'opérateur du diagnostic.
      LOG.warn("Prévision indisponible pour le diagnostic de {} : {}", equipmentId, e.getMessage());
      return null;
    }
  }

  // ------------------------------------------------------------------ incidents similaires

  private IncidentsSimilaires similar(AlertFacts alert, Instant now) {
    var key =
        new SimilarKey(
            alert.type(),
            alert.ruleId(),
            alert.eventRuleId(),
            MetricKeys.occurrencePrefix(alert.type(), alert.occurrenceKey()));
    Instant since = now.minus(Duration.ofDays(SIMILAR_DAYS));
    String criterion = "MEME_EQUIPEMENT";
    UUID equipment = alert.equipmentId();
    SimilarStats stats = repository.similarStats(key, alert.id(), equipment, null, since);
    // Rien sur l'équipement : même règle sur le même site (règles de seuil et d'événement).
    if (stats.count() == 0 && (alert.ruleId() != null || alert.eventRuleId() != null)) {
      SimilarStats bySite = repository.similarStats(key, alert.id(), null, alert.siteId(), since);
      if (bySite.count() > 0) {
        criterion = "MEME_SITE";
        equipment = null;
        stats = bySite;
      }
    }
    List<Resolution> last =
        stats.resolved() == 0
            ? List.of()
            : repository
                .lastResolutions(
                    key, alert.id(), equipment, alert.siteId(), since, LAST_RESOLUTIONS)
                .stream()
                .map(
                    r ->
                        new Resolution(
                            r.id(),
                            r.triggeredAt(),
                            r.resolvedAt(),
                            Duration.between(r.triggeredAt(), r.resolvedAt()).toMinutes(),
                            r.resolutionType(),
                            r.author(),
                            DiagnosticTexts.clean(r.comment(), 500)))
                .toList();
    return new IncidentsSimilaires(
        criterion,
        stats.count(),
        stats.resolved(),
        stats.median() == null ? null : round(stats.median(), 1),
        stats.mean() == null ? null : round(stats.mean(), 1),
        last);
  }

  // ------------------------------------------------------------------ résumé

  /**
   * Résumé en trois ou quatre phrases fixes : ce qui se passe, ce que ça touche, la cause la plus
   * probable (ou son absence), les incidents similaires s'il y en a. Une phrase n'apparaît que si
   * le fait qu'elle énonce est dans la réponse.
   */
  private static String resume(
      AlertFacts alert,
      Impact impact,
      List<Scored> causes,
      ContexteMetrique context,
      IncidentsSimilaires similar,
      DiagnosticTexts texts) {
    var parts = new ArrayList<String>();
    parts.add(subjectSentence(alert, context, texts));
    if (!impact.racine()) {
      parts.add(
          impact.alerteRacineId() == null
              ? "Cette alerte est regroupée sous une alerte racine hors de votre périmètre."
              : "Cette alerte est regroupée sous une alerte racine ("
                  + impact.raisonRegroupement()
                  + ").");
    }
    if (impact.equipementsEnAval() == 1) {
      parts.add("1 équipement en aval est touché.");
    } else if (impact.equipementsEnAval() > 1) {
      parts.add(impact.equipementsEnAval() + " équipements en aval sont touchés.");
    }
    if (impact.alertesRegroupees() == 1) {
      parts.add("1 autre alerte lui est rattachée.");
    } else if (impact.alertesRegroupees() > 1) {
      parts.add(impact.alertesRegroupees() + " autres alertes lui sont rattachées.");
    }
    if (causes.isEmpty()) {
      parts.add("Aucune cause probable identifiée dans les données disponibles.");
    } else {
      CauseCandidate top = causes.get(0).candidate();
      parts.add(
          "Cause probable : "
              + top.libelle()
              + (top.at() == null ? "" : " (" + texts.time(top.at()) + ")")
              + ".");
    }
    if (similar.nombre() > 0) {
      parts.add(
          DiagnosticTexts.plural(similar.nombre(), "incident similaire", "incidents similaires")
              + " en "
              + SIMILAR_DAYS
              + " jours"
              + (similar.medianeMinutes() == null
                  ? ""
                  : ", résolution médiane en " + minutes(similar.medianeMinutes()))
              + ".");
    }
    return String.join(" ", parts);
  }

  private static String subjectSentence(
      AlertFacts alert, ContexteMetrique context, DiagnosticTexts texts) {
    String subject =
        DiagnosticTexts.subject(
            alert.equipmentType(), DiagnosticTexts.clean(alert.equipmentName(), 120));
    String since = texts.time(alert.openedAt());
    boolean resolved = alert.resolvedAt() != null;
    String period =
        resolved ? "de " + since + " à " + texts.time(alert.resolvedAt()) : "depuis " + since;
    String message = DiagnosticTexts.clean(alert.message(), 200);
    return switch (alert.type()) {
      case "COLLECTION" ->
          subject + (resolved ? " a été injoignable " : " est injoignable ") + period + ".";
      case "THRESHOLD" ->
          subject
              + (resolved ? " a été en alerte" : " est en alerte")
              + " sur la règle « "
              + DiagnosticTexts.clean(alert.ruleName(), 120)
              + " » "
              + period
              + thresholdDetail(alert, context)
              + ".";
      case "FORECAST" -> subject + " : " + message + " (prévision émise à " + since + ").";
      case "ANOMALY" ->
          subject
              + (resolved ? " a présenté" : " présente")
              + " un comportement inhabituel "
              + period
              + " : "
              + message
              + ".";
      case "EVENT" -> subject + " a signalé un événement à " + since + " : " + message + ".";
      default -> subject + " : " + message + " (" + period + ").";
    };
  }

  /** « (valeur 95 %, seuil > 90 %) » si la valeur à l'ouverture est connue. */
  private static String thresholdDetail(AlertFacts alert, ContexteMetrique context) {
    if (context == null || context.valeurOuverture() == null || alert.threshold() == null) {
      return "";
    }
    String unit =
        context.unite() == null || context.unite().isBlank()
            ? ""
            : ("%".equals(context.unite())
                ? " %"
                : " " + DiagnosticTexts.clean(context.unite(), 24));
    return " (valeur "
        + number(context.valeurOuverture())
        + unit
        + ", seuil "
        + OPERATORS.getOrDefault(alert.operator(), "")
        + " "
        + number(alert.threshold())
        + unit
        + ")";
  }

  // ------------------------------------------------------------------ pistes

  private List<String> nextSteps(AlertFacts alert, String metricKey, List<Scored> causes) {
    var context =
        new NextStepProvider.Context(
            alert.id(),
            alert.type(),
            alert.ruleId(),
            alert.eventRuleId(),
            metricKey,
            "COLLECTION".equals(alert.type()) ? CauseFinder.mode(alert.message()).name() : null,
            causes);
    var merged = new LinkedHashSet<String>();
    for (NextStepProvider provider : steps) {
      merged.addAll(provider.steps(context));
    }
    // Toujours deux pistes au moins, même si un fournisseur ajouté plus tard n'en donne pas.
    merged.add(NextStepCatalog.HISTORY);
    merged.add(NextStepCatalog.DOCUMENT);
    return merged.stream().limit(MAX_STEPS).toList();
  }

  // ------------------------------------------------------------------ conversions

  private static CauseProbable cause(Scored scored) {
    CauseCandidate c = scored.candidate();
    return new CauseProbable(
        c.source(),
        scored.score(),
        c.libelle(),
        c.raisons(),
        c.equipementId(),
        c.alerteId(),
        c.at());
  }

  private static EvenementRecent event(EventRow row) {
    return new EvenementRecent(
        row.id(),
        row.equipmentId(),
        DiagnosticTexts.clean(row.equipmentName(), 120),
        row.source(),
        row.severity(),
        DiagnosticTexts.clean(row.title(), 255),
        row.receivedAt(),
        row.upstream() ? Relation.AMONT : Relation.MEME_EQUIPEMENT);
  }

  private static ChangementRecent change(ChangeRow row) {
    return new ChangementRecent(
        row.action(),
        DiagnosticTexts.changeLabel(row.action()),
        DiagnosticTexts.clean(row.username(), 64),
        row.performedAt(),
        DiagnosticTexts.target(row.targetType(), row.targetName()));
  }

  private static double round(double value, int scale) {
    return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
  }

  private static String number(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',');
  }

  private static String minutes(double value) {
    long rounded = Math.round(value);
    return rounded < 120 ? rounded + " min" : number(round(value / 60.0, 1)) + " h";
  }
}
