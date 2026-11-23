package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.IncidentState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.service.IncidentGroupingService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Regroupement des alertes en incidents (M10) sur une vraie base : ce que chaque règle rapproche,
 * ce qu'aucune ne rapproche, et qui prend la parole.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IncidentGroupingTests {
  @Autowired private IncidentGroupingService grouping;
  @Autowired private AlertRepository alerts;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site site;
  private Site other;
  private Equipment router;
  private Equipment switchA;
  private Equipment switchB;
  private Equipment elsewhere;
  private Instant now;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    site = sites.saveAndFlush(new Site("INC-" + unique, "Site incidents"));
    other = sites.saveAndFlush(new Site("INC2-" + unique, "Autre site"));
    router = equipment("Routeur de coeur", EquipmentType.ROUTER, site, 1);
    switchA = equipment("Commutateur Bloc A", EquipmentType.SWITCH, site, 2);
    switchB = equipment("Commutateur Bloc B", EquipmentType.SWITCH, site, 3);
    elsewhere = equipment("Serveur distant", EquipmentType.SERVER, other, 4);
    // Topologie : le routeur est l'amont des deux commutateurs. Bloc B est donc a deux liens de
    // Bloc A, en passant par le routeur.
    link(switchA, router);
    link(switchB, router);
    em.flush();
    now = Instant.now();
  }

  private Equipment equipment(String name, EquipmentType type, Site where, int suffix) {
    String unique = UUID.randomUUID().toString();
    return equipments.saveAndFlush(
        new Equipment(
            "INC-" + unique,
            name,
            "10.97." + (Math.floorMod(unique.hashCode(), 250) + 1) + "." + suffix,
            type,
            EquipmentStatus.UP,
            where));
  }

  private void link(Equipment downstream, Equipment upstream) {
    jdbc.update(
        "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        downstream.getId(),
        upstream.getId());
  }

  /** Ouvre une alerte comme les détecteurs le font, sans passer par leurs règles. */
  private Alert alert(Equipment on, AlertType type, Severity severity, String message) {
    var alert =
        new Alert(
            on,
            "test:" + UUID.randomUUID(),
            type,
            AlertState.OPEN,
            severity,
            message,
            Instant.now());
    alerts.saveAndFlush(alert);
    return alert;
  }

  private IncidentGroupingService.Attachment attach(Alert alert) {
    return attach(alert, true);
  }

  private IncidentGroupingService.Attachment attach(Alert alert, boolean mayAnnounce) {
    em.flush();
    return grouping.attach(alert.getId(), now, mayAnnounce).orElseThrow();
  }

  private List<String> reasons(UUID incidentId) {
    em.flush();
    return jdbc.queryForList(
        "SELECT reason FROM incident_alert WHERE incident_id = ? ORDER BY joined_at",
        String.class,
        incidentId);
  }

  @Test
  @DisplayName("La première alerte ouvre un incident, nommé d'après son message")
  void thefirstAlertOpensAnincidentNamedAfterItsMessage() {
    var first = alert(router, AlertType.COLLECTION, Severity.CRITICAL, "Perte de collecte");

    var attachment = attach(first);

    assertThat(attachment.created()).isTrue();
    assertThat(attachment.announces()).isTrue();
    assertThat(attachment.reason()).isEqualTo("Première alerte de l'incident");
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT title FROM incident WHERE id = ?", String.class, attachment.incidentId()))
        .isEqualTo("Perte de collecte");
  }

  @Test
  @DisplayName("Règle 1 : deux alertes sur la même machine, un seul incident, une seule voix")
  void ruleOneTwoalertsOnTheSameMachineOneincidentOnevoice() {
    var first = alert(router, AlertType.ANOMALY, Severity.WARNING, "Processeur inhabituel");
    var second = alert(router, AlertType.ANOMALY, Severity.WARNING, "Mémoire inhabituelle");

    var opened = attach(first);
    var joined = attach(second);

    assertThat(joined.incidentId()).isEqualTo(opened.incidentId());
    assertThat(joined.created()).isFalse();
    assertThat(joined.reason()).isEqualTo("Même équipement : Routeur de coeur");
    // Un fait, une notification : la seconde alerte est visible mais ne réveille personne.
    assertThat(joined.announces()).isFalse();
    assertThat(grouping.announces(second.getId())).isFalse();
    assertThat(grouping.announces(first.getId())).isTrue();
  }

  @Test
  @DisplayName("Règle 2 : un voisin direct dans la topologie rejoint l'incident, lien nommé")
  void ruleTwoAdirectNeighbourJoinsTheincident() {
    var first = alert(router, AlertType.COLLECTION, Severity.CRITICAL, "Perte de collecte");
    var second = alert(switchA, AlertType.NO_DATA, Severity.WARNING, "Plus de données reçues");

    var opened = attach(first);
    var joined = attach(second);

    assertThat(joined.incidentId()).isEqualTo(opened.incidentId());
    assertThat(joined.reason())
        .isEqualTo("Voisin dans la topologie : Routeur de coeur (lien direct)");
  }

  @Test
  @DisplayName("Règle 2 : le parcours est non orienté et compte les liens traversés")
  void ruleTwoThewalkIsUndirectedAndCountsTheLinks() {
    // Bloc A et Bloc B ne sont pas liés entre eux : ils le sont par le routeur, à deux liens.
    var first = alert(switchA, AlertType.ANOMALY, Severity.WARNING, "Débit entrant saturé");
    var second = alert(switchB, AlertType.ANOMALY, Severity.WARNING, "Erreurs en hausse");

    var opened = attach(first);
    var joined = attach(second);

    assertThat(joined.incidentId()).isEqualTo(opened.incidentId());
    assertThat(joined.reason())
        .isEqualTo("Voisin dans la topologie : Commutateur Bloc A (2 liens)");
  }

  @Test
  @DisplayName("Règle 3 : même site et gabarit identique, sans lien de topologie")
  void ruleThreeSameSiteAndSameTemplateWithoutAtopologyLink() {
    // Deux machines du même site que rien ne relie dans la topologie.
    var lonelyA = equipment("Borne Wi-Fi Est", EquipmentType.WIFI_ACCESS_POINT, site, 11);
    var lonelyB = equipment("Borne Wi-Fi Ouest", EquipmentType.WIFI_ACCESS_POINT, site, 12);
    em.flush();
    var first =
        alert(lonelyA, AlertType.ANOMALY, Severity.WARNING, "Débit entrant inhabituel : 94 %");
    var second =
        alert(lonelyB, AlertType.ANOMALY, Severity.WARNING, "Débit entrant inhabituel : 91 %");

    var opened = attach(first);
    var joined = attach(second);

    assertThat(joined.incidentId()).isEqualTo(opened.incidentId());
    assertThat(joined.reason())
        .startsWith("Même site et alerte semblable : Borne Wi-Fi Est")
        .contains("100 % de mots communs");
  }

  @Test
  @DisplayName("Règle 3 : deux faits différents du même site restent deux incidents")
  void ruleThreeTwodifferentFactsOnTheSameSiteStayTwoincidents() {
    var lonelyA = equipment("Borne Wi-Fi Est", EquipmentType.WIFI_ACCESS_POINT, site, 21);
    var lonelyB = equipment("Borne Wi-Fi Ouest", EquipmentType.WIFI_ACCESS_POINT, site, 22);
    em.flush();
    var first = alert(lonelyA, AlertType.ANOMALY, Severity.WARNING, "Processeur inhabituel : 92 %");
    var second = alert(lonelyB, AlertType.FORECAST, Severity.WARNING, "Disque plein dans ~9 jours");

    var opened = attach(first);
    var joined = attach(second);

    // La fenêtre de temps seule ne fait pas un incident : sans quoi tout ce qui arrive en même
    // temps sur un site finirait dans le même sac.
    assertThat(joined.incidentId()).isNotEqualTo(opened.incidentId());
    assertThat(joined.created()).isTrue();
    assertThat(joined.announces()).isTrue();
  }

  @Test
  @DisplayName("Un autre site, sans lien de topologie, n'est jamais le même incident")
  void anothersiteWithoutAtopologyLinkIsNeverTheSameincident() {
    var first = alert(router, AlertType.COLLECTION, Severity.CRITICAL, "Perte de collecte");
    var second = alert(elsewhere, AlertType.COLLECTION, Severity.CRITICAL, "Perte de collecte");

    var opened = attach(first);
    var joined = attach(second);

    assertThat(joined.incidentId()).isNotEqualTo(opened.incidentId());
  }

  @Test
  @DisplayName("Hors de la fenêtre de temps, c'est un nouvel incident")
  void outsideThetimeWindowItisAnewincident() {
    var first = alert(router, AlertType.ANOMALY, Severity.WARNING, "Processeur inhabituel");
    var opened = grouping.attach(first.getId(), now.minus(Duration.ofHours(2)), true).orElseThrow();
    var second = alert(router, AlertType.ANOMALY, Severity.WARNING, "Mémoire inhabituelle");

    var joined = attach(second);

    assertThat(joined.incidentId()).isNotEqualTo(opened.incidentId());
  }

  @Test
  @DisplayName("Un incident qui s'aggrave redonne la parole, une seule fois")
  void anincidentThatWorsensGivesTheVoiceBackOnce() {
    var first = alert(router, AlertType.ANOMALY, Severity.WARNING, "Processeur inhabituel");
    var worse = alert(router, AlertType.COLLECTION, Severity.CRITICAL, "Perte de collecte");
    var alsoCritical = alert(router, AlertType.ANOMALY, Severity.CRITICAL, "Mémoire inhabituelle");

    attach(first);
    var escalated = attach(worse);
    var third = attach(alsoCritical);

    // Un incident notifié en avertissement qui devient critique doit réveiller quelqu'un.
    assertThat(escalated.announces()).isTrue();
    // Mais une seconde alerte critique du même incident, non : rien de nouveau n'est appris.
    assertThat(third.announces()).isFalse();
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT severity FROM incident WHERE id = ?", String.class, escalated.incidentId()))
        .isEqualTo("CRITICAL");
  }

  @Test
  @DisplayName("Une alerte muette ne consomme pas la voix de l'incident")
  void amuteAlertDoesNotConsumeTheincidentVoice() {
    // Alerte sous maintenance, ou déjà regroupée par l'ADR 0012 : elle ne publie pas l'événement
    // de notification, elle entre donc dans l'incident sans prendre la parole.
    var silent = alert(router, AlertType.ANOMALY, Severity.WARNING, "Processeur inhabituel");
    var speaking = alert(router, AlertType.ANOMALY, Severity.WARNING, "Mémoire inhabituelle");

    var opened = attach(silent, false);
    var joined = attach(speaking, true);

    assertThat(opened.announces()).isFalse();
    assertThat(joined.incidentId()).isEqualTo(opened.incidentId());
    // Sans cette distinction, la première alerte aurait emporté la voix et personne n'aurait été
    // prévenu de la seconde.
    assertThat(joined.announces()).isTrue();
  }

  @Test
  @DisplayName("Rattacher deux fois la même alerte ne change rien")
  void attachingTheSameAlertTwiceChangesNothing() {
    var first = alert(router, AlertType.ANOMALY, Severity.WARNING, "Processeur inhabituel");

    var once = attach(first);
    var twice = attach(first);

    assertThat(twice.incidentId()).isEqualTo(once.incidentId());
    assertThat(twice.created()).isFalse();
    assertThat(twice.announces()).isEqualTo(once.announces());
    assertThat(grouping.size(once.incidentId())).isEqualTo(1);
  }

  @Test
  @DisplayName("L'incident se referme quand toutes ses alertes sont retombées, pas avant")
  void theincidentClosesWhenAllItsAlertsAreBackNotBefore() {
    var first = alert(router, AlertType.ANOMALY, Severity.WARNING, "Processeur inhabituel");
    var second = alert(router, AlertType.ANOMALY, Severity.WARNING, "Mémoire inhabituelle");
    var opened = attach(first);
    attach(second);

    resolve(first);
    grouping.settle(first.getId(), now);
    assertThat(state(opened.incidentId())).isEqualTo(IncidentState.OPEN);

    resolve(second);
    grouping.settle(second.getId(), now);
    assertThat(state(opened.incidentId())).isEqualTo(IncidentState.RESOLVED);
  }

  @Test
  @DisplayName("Chaque rattachement porte sa raison en clair")
  void everyAttachmentCarriesItsReasonInPlainWords() {
    var first = alert(router, AlertType.COLLECTION, Severity.CRITICAL, "Perte de collecte");
    var second = alert(switchA, AlertType.NO_DATA, Severity.WARNING, "Plus de données reçues");
    var third = alert(router, AlertType.ANOMALY, Severity.WARNING, "Processeur inhabituel");

    var opened = attach(first);
    attach(second);
    attach(third);

    assertThat(reasons(opened.incidentId()))
        .containsExactly(
            "Première alerte de l'incident",
            "Voisin dans la topologie : Routeur de coeur (lien direct)",
            "Même équipement : Routeur de coeur");
  }

  @Test
  @DisplayName("Une alerte que JPA n'a pas encore écrite est rangée quand même")
  void analertNotYetWrittenByJpaIsFiledAllTheSame() {
    // Les détecteurs enregistrent l'alerte puis publient son événement sans vider la session :
    // le regroupement relit en SQL et ne voyait rien. Constaté sur l'instance, invisible ici
    // parce que les autres cas de ce fichier vident la session avant d'appeler. Pas de flush ici,
    // volontairement.
    var alert =
        new Alert(
            router,
            "test:" + UUID.randomUUID(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            Severity.WARNING,
            "Processeur inhabituel",
            Instant.now());
    alerts.save(alert);

    var attachment = grouping.attach(alert.getId(), now, true);

    assertThat(attachment).isPresent();
    assertThat(attachment.orElseThrow().created()).isTrue();
  }

  @Test
  @DisplayName("Une alerte introuvable ne casse pas le regroupement")
  void anunknownAlertDoesNotBreakTheGrouping() {
    assertThat(grouping.attach(UUID.randomUUID(), now, true)).isEmpty();
    assertThat(grouping.announces(UUID.randomUUID())).isTrue();
  }

  private void resolve(Alert alert) {
    alert.setState(AlertState.RESOLVED);
    alert.setResolvedAt(now);
    alert.setResolutionType(sn.ept.transdic1.alerting.domain.ResolutionType.AUTOMATIC);
    alerts.saveAndFlush(alert);
  }

  private IncidentState state(UUID incidentId) {
    em.flush();
    return IncidentState.valueOf(
        jdbc.queryForObject("SELECT state FROM incident WHERE id = ?", String.class, incidentId));
  }
}
