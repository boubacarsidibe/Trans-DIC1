package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.diagnostic.domain.CauseSource;
import sn.ept.transdic1.diagnostic.domain.Relation;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.CauseProbable;
import sn.ept.transdic1.diagnostic.service.DiagnosticService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Diagnostic d'incident (F2.1, ADR 0024) sur données réelles : une cause par source (amont en
 * panne, trap linkDown, règle modifiée, prévision, anomalie, silence de collecte), périmètre par
 * site, plafonds, absence de données sensibles et résumé fidèle aux seuls faits présents.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DiagnosticServiceTests {
  private static final String LINK_DOWN = "1.3.6.1.6.3.1.1.5.3";

  @Autowired private DiagnosticService diagnostic;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRuleRepository rules;
  @Autowired private UserRepository users;
  @Autowired private SiteAccessRepository access;
  @Autowired private JdbcTemplate jdbc;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  // Ouverture de référence : il y a deux heures, à la minute près.
  private final Instant t0 =
      Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
  private Site siteA;
  private Site siteB;
  private int host;

  @BeforeEach
  void data() {
    siteA = sites.saveAndFlush(new Site("DGA-" + suffix, "Site A " + suffix));
    siteB = sites.saveAndFlush(new Site("DGB-" + suffix, "Site B " + suffix));
  }

  @AfterEach
  void logout() {
    SecurityContextHolder.clearContext();
  }

  // ------------------------------------------------------------------ données

  private Equipment equipment(String name, EquipmentType type, Site site) {
    host++;
    return equipments.saveAndFlush(
        new Equipment(
            "DG-" + suffix + "-" + host,
            name + " " + suffix,
            "203.0." + (Math.floorMod(suffix.hashCode(), 200) + 20) + "." + host,
            type,
            EquipmentStatus.UP,
            site));
  }

  private void link(Equipment child, Equipment upstream) {
    jdbc.update(
        "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        child.getId(),
        upstream.getId());
  }

  private AlertRule rule(String metric) {
    return rules.saveAndFlush(
        new AlertRule(
            "Règle " + metric + " " + UUID.randomUUID().toString().substring(0, 8),
            metric,
            ComparisonOperator.GREATER_THAN,
            BigDecimal.valueOf(90),
            Duration.ZERO,
            Severity.CRITICAL));
  }

  private UUID alert(Equipment eq, String type, UUID ruleId, Instant at, String message) {
    return alert(eq, type, ruleId, at, null, message, null, null, type.toLowerCase() + ":");
  }

  private UUID alert(
      Equipment eq,
      String type,
      UUID ruleId,
      Instant at,
      Instant resolvedAt,
      String message,
      UUID parent,
      String reason,
      String keyPrefix) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, rule_id, occurrence_key, type, state, severity,"
            + " message, triggered_at, resolved_at, resolution_type, parent_alert_id,"
            + " correlation_reason) VALUES (?, ?, ?, ?, ?, ?, 'CRITICAL', ?, ?, ?, ?, ?, ?)",
        id,
        eq.getId(),
        ruleId,
        keyPrefix + id,
        type,
        resolvedAt == null ? "OPEN" : "RESOLVED",
        message,
        Timestamp.from(at),
        resolvedAt == null ? null : Timestamp.from(resolvedAt),
        resolvedAt == null ? null : "MANUAL",
        parent,
        reason);
    return id;
  }

  private UUID event(Equipment eq, String title, String oid, String severity, Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO network_event(id, equipment_id, source, severity, source_address, title,"
            + " details, trap_oid, received_at) VALUES (?, ?, ?, ?, '192.0.2.1', ?, ?, ?, ?)",
        id,
        eq.getId(),
        oid == null ? "SYSLOG" : "SNMP_TRAP",
        severity,
        title,
        "communaute=public-secret",
        oid,
        Timestamp.from(at));
    return id;
  }

  private void audit(String action, String targetType, UUID target, Instant at, String details) {
    jdbc.update(
        "INSERT INTO audit_entry(id, username, action, target_type, target_id, details_sanitized,"
            + " performed_at) VALUES (?, 'alice', ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        action,
        targetType,
        target,
        details,
        Timestamp.from(at));
  }

  private void metric(Equipment eq, String key, double value, Instant at) {
    jdbc.update(
        "INSERT INTO metric(equipment_id, metric_key, metric_value, unit, quality,"
            + " collected_at, received_at) VALUES (?, ?, ?, '%', 'GOOD', ?, ?)",
        eq.getId(), key, BigDecimal.valueOf(value), Timestamp.from(at), Timestamp.from(at));
  }

  private void resolvedEvent(UUID alertId, Instant at, String comment) {
    jdbc.update(
        "INSERT INTO alert_event(id, alert_id, type, previous_state, new_state, comment,"
            + " occurred_at) VALUES (?, ?, 'RESOLVED', 'OPEN', 'RESOLVED', ?, ?)",
        UUID.randomUUID(),
        alertId,
        comment,
        Timestamp.from(at));
  }

  private void loginLimitedTo(Site site) {
    var user =
        users.saveAndFlush(
            new AppUser(
                "dg-noc-" + suffix, "dg-noc-" + suffix + "@example.test", "x", Role.NOC_OPERATOR));
    access.saveAndFlush(new SiteAccess(user, site, Instant.now()));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                user.getUsername(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_NOC_OPERATOR"))));
  }

  private static CauseProbable first(DiagnosticResponse d) {
    assertThat(d.causesProbables()).isNotEmpty();
    return d.causesProbables().get(0);
  }

  private static List<CauseSource> sources(DiagnosticResponse d) {
    return d.causesProbables().stream().map(CauseProbable::source).toList();
  }

  // ------------------------------------------------------------------ causes

  @Test
  void unAmontEnPanneEstLaCauseRacineParLaTopologie() {
    Equipment core = equipment("Routeur cœur", EquipmentType.ROUTER, siteA);
    Equipment sw = equipment("Commutateur", EquipmentType.SWITCH, siteA);
    Equipment server = equipment("Serveur", EquipmentType.SERVER, siteA);
    link(sw, core);
    link(server, sw);
    UUID root = alert(core, "COLLECTION", null, t0.minusSeconds(60), "Plus de réponse");
    UUID child =
        alert(
            sw,
            "COLLECTION",
            null,
            t0,
            null,
            "Plus de réponse",
            root,
            "Amont injoignable : " + core.getName(),
            "collection:");

    DiagnosticResponse d = diagnostic.diagnose(child);

    CauseProbable cause = first(d);
    assertThat(cause.source()).isEqualTo(CauseSource.AMONT_EN_ALERTE);
    assertThat(cause.libelle()).contains(core.getName());
    assertThat(cause.alerteId()).isEqualTo(root);
    assertThat(cause.raisons()).anySatisfy(r -> assertThat(r).contains(core.getName()));
    assertThat(cause.score()).isBetween(0.9, 1.0);
    assertThat(d.impact().racine()).isFalse();
    assertThat(d.impact().alerteRacineId()).isEqualTo(root);
    assertThat(d.impact().equipementsEnAval()).isEqualTo(1);
    assertThat(d.impact().equipements())
        .extracting(DiagnosticResponse.EquipementTouche::nom)
        .containsExactly(server.getName());
    assertThat(d.alertesVoisines())
        .anySatisfy(
            v -> {
              assertThat(v.id()).isEqualTo(root);
              assertThat(v.relation()).isEqualTo(Relation.AMONT);
            });
    assertThat(d.resume())
        .contains(sw.getName())
        .contains("injoignable")
        .contains("1 équipement en aval est touché")
        .contains("Cause probable")
        .contains(core.getName());

    DiagnosticResponse rootDiagnostic = diagnostic.diagnose(root);
    assertThat(rootDiagnostic.impact().racine()).isTrue();
    assertThat(rootDiagnostic.impact().alertesRegroupees()).isEqualTo(1);
    assertThat(rootDiagnostic.impact().equipementsEnAval()).isEqualTo(2);
    assertThat(rootDiagnostic.resume()).contains("2 équipements en aval sont touchés");
    assertThat(rootDiagnostic.alertesVoisines())
        .anySatisfy(v -> assertThat(v.relation()).isEqualTo(Relation.AVAL));
  }

  @Test
  void unTrapLinkDownUneMinuteAvantEstLaCauseProbable() {
    Equipment router = equipment("Routeur agence", EquipmentType.ROUTER, siteA);
    UUID trap =
        event(router, "linkDown (interface coupée)", LINK_DOWN, "ERROR", t0.minusSeconds(60));
    // Un événement après l'ouverture n'explique rien ; un événement hors fenêtre non plus.
    event(
        router, "linkDown (interface coupée)", LINK_DOWN, "ERROR", t0.plus(Duration.ofMinutes(10)));
    event(router, "linkDown (interface coupée)", LINK_DOWN, "ERROR", t0.minus(Duration.ofHours(1)));
    UUID id = alert(router, "COLLECTION", null, t0, "Équipement injoignable : 3 échecs");

    DiagnosticResponse d = diagnostic.diagnose(id);

    CauseProbable cause = first(d);
    assertThat(cause.source()).isEqualTo(CauseSource.EVENEMENT_RECENT);
    assertThat(cause.libelle()).contains("linkDown");
    assertThat(cause.raisons()).anySatisfy(r -> assertThat(r).contains("linkDown"));
    assertThat(cause.survenueLe()).isEqualTo(t0.minusSeconds(60));
    assertThat(sources(d)).containsOnlyOnce(CauseSource.EVENEMENT_RECENT);
    assertThat(d.evenementsRecents())
        .extracting(DiagnosticResponse.EvenementRecent::id)
        .contains(trap)
        .hasSize(2);
    assertThat(d.resume()).contains("linkDown");
    assertThat(d.pistes()).anySatisfy(p -> assertThat(p).containsIgnoringCase("câble"));
    // Les détails bruts de l'événement (qui peuvent contenir une communauté) ne sortent pas.
    assertThat(serialized(d)).doesNotContain("public-secret");
  }

  @Test
  void uneRegleModifieeCinqMinutesAvantEstUnChangementSuspectSansValeurSensible() {
    Equipment server = equipment("Serveur web", EquipmentType.SERVER, siteA);
    AlertRule cpu = rule("cpu.usage");
    AlertRule other = rule("memory.usage");
    audit(
        "RULE_UPDATED",
        "ALERT_RULE",
        cpu.getId(),
        t0.minus(Duration.ofMinutes(5)),
        "nom=x ; avant: seuil=80 ; après: seuil=90 motdepasse=hunter2");
    audit("RULE_UPDATED", "ALERT_RULE", other.getId(), t0.minusSeconds(120), "autre règle");
    // Action hors liste (lecture d'audit, connexion) : jamais présentée comme un changement.
    audit("AUDIT_EXPORTED", "AUDIT", null, t0.minusSeconds(30), "lignes=3");
    UUID id = alert(server, "THRESHOLD", cpu.getId(), t0, "CPU haut : cpu.usage = 95");

    DiagnosticResponse d = diagnostic.diagnose(id);

    CauseProbable cause = first(d);
    assertThat(cause.source()).isEqualTo(CauseSource.CHANGEMENT_RECENT);
    assertThat(cause.raisons()).anySatisfy(r -> assertThat(r).contains("alice"));
    assertThat(d.changementsRecents()).hasSize(1);
    var change = d.changementsRecents().get(0);
    assertThat(change.action()).isEqualTo("RULE_UPDATED");
    assertThat(change.auteur()).isEqualTo("alice");
    assertThat(change.cible()).contains(cpu.getName());
    assertThat(change.effectueLe()).isEqualTo(t0.minus(Duration.ofMinutes(5)));
    String text = serialized(d);
    assertThat(text)
        .doesNotContain("hunter2")
        .doesNotContain("seuil=80")
        .doesNotContain("lignes=3");
  }

  @Test
  void unePrevisionDeSaturationExpliqueLAlerteEtDonneSonBlocDePrevision() {
    Equipment server = equipment("Serveur fichiers", EquipmentType.SERVER, siteA);
    // Disque qui croît de 0,5 point par heure depuis cinq jours : saturation prévue.
    Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
    for (int h = 120; h >= 1; h--) {
      metric(server, "disk.usage", 20 + (120 - h) * 0.5, now.minus(Duration.ofHours(h)));
    }
    UUID forecast =
        alert(
            server,
            "FORECAST",
            null,
            t0.minus(Duration.ofHours(3)),
            null,
            "Disque plein dans ~3 jours (tendance +12,0 %/jour, confiance haute)",
            null,
            null,
            "forecast:" + server.getId() + ":disk.usage@");

    DiagnosticResponse d = diagnostic.diagnose(forecast);

    assertThat(first(d).source()).isEqualTo(CauseSource.TENDANCE_SATURATION);
    assertThat(d.contexteMetrique()).isNotNull();
    assertThat(d.contexteMetrique().metrique()).isEqualTo("disk.usage");
    assertThat(d.contexteMetrique().prevision()).isNotNull();
    assertThat(d.contexteMetrique().prevision().key()).isEqualTo("disk.usage");
    assertThat(d.pistes()).anySatisfy(p -> assertThat(p).containsIgnoringCase("disque"));

    // Une alerte de seuil sur le même disque s'explique aussi par la tendance en cours.
    AlertRule disk = rule("disk.usage");
    UUID threshold = alert(server, "THRESHOLD", disk.getId(), t0, "Disque haut");
    assertThat(sources(diagnostic.diagnose(threshold))).contains(CauseSource.TENDANCE_SATURATION);
  }

  @Test
  void uneAnomalieOuverteJusteAvantFigureParmiLesCauses() {
    Equipment server = equipment("Serveur calcul", EquipmentType.SERVER, siteA);
    UUID anomaly =
        alert(
            server,
            "ANOMALY",
            null,
            t0.minus(Duration.ofMinutes(3)),
            null,
            "Comportement inhabituel : cpu.usage",
            null,
            null,
            "anomaly:" + server.getId() + ":cpu.usage:");
    UUID id = alert(server, "THRESHOLD", rule("cpu.usage").getId(), t0, "CPU haut");

    DiagnosticResponse d = diagnostic.diagnose(id);

    assertThat(d.causesProbables())
        .anySatisfy(
            c -> {
              assertThat(c.source()).isEqualTo(CauseSource.ANOMALIE);
              assertThat(c.alerteId()).isEqualTo(anomaly);
              assertThat(c.raisons()).isNotEmpty();
            });
  }

  @Test
  void unSilenceDeCollecteEstExpliqueSelonLeModeDeCollecte() {
    Equipment server = equipment("Serveur agent", EquipmentType.SERVER, siteA);
    UUID silence =
        alert(
            server,
            "COLLECTION",
            null,
            t0,
            "L'agent de " + server.getName() + " ne communique plus depuis 5 min");

    DiagnosticResponse d = diagnostic.diagnose(silence);

    CauseProbable cause = first(d);
    assertThat(cause.source()).isEqualTo(CauseSource.SILENCE_COLLECTE);
    assertThat(cause.libelle()).containsIgnoringCase("agent");
    assertThat(d.pistes()).anySatisfy(p -> assertThat(p).containsIgnoringCase("agent"));

    // Une alerte de seuil pendant le silence : les mesures manquent, c'est une cause.
    UUID threshold =
        alert(server, "THRESHOLD", rule("cpu.usage").getId(), t0.plusSeconds(120), "CPU haut");
    assertThat(sources(diagnostic.diagnose(threshold))).contains(CauseSource.SILENCE_COLLECTE);
  }

  // ------------------------------------------------------------------ périmètre

  @Test
  void unCompteLimiteNeVoitRienDesSitesHorsDeSonPerimetre() {
    Equipment coreB = equipment("Cœur site B", EquipmentType.ROUTER, siteB);
    Equipment swA = equipment("Commutateur A", EquipmentType.SWITCH, siteA);
    Equipment downB = equipment("Borne B", EquipmentType.WIFI_ACCESS_POINT, siteB);
    Equipment downA = equipment("Borne A", EquipmentType.WIFI_ACCESS_POINT, siteA);
    link(swA, coreB);
    link(downB, swA);
    link(downA, swA);
    UUID rootB = alert(coreB, "COLLECTION", null, t0.minusSeconds(60), "Plus de réponse");
    event(coreB, "linkDown (interface coupée)", LINK_DOWN, "ERROR", t0.minusSeconds(90));
    audit("EQUIPMENT_UPDATED", "EQUIPMENT", coreB.getId(), t0.minusSeconds(200), "code=x");
    alert(downB, "COLLECTION", null, t0.plusSeconds(30), "Plus de réponse");
    UUID onA =
        alert(
            swA,
            "COLLECTION",
            null,
            t0,
            null,
            "Plus de réponse",
            rootB,
            "Amont injoignable : " + coreB.getName(),
            "collection:");
    UUID alertOnB = alert(downB, "THRESHOLD", rule("cpu.usage").getId(), t0, "CPU haut");

    // Administrateur (aucun contexte de sécurité = tâche interne) : tout est visible.
    DiagnosticResponse all = diagnostic.diagnose(onA);
    assertThat(sources(all)).contains(CauseSource.AMONT_EN_ALERTE);
    assertThat(all.impact().equipementsEnAval()).isEqualTo(2);

    loginLimitedTo(siteA);
    DiagnosticResponse d = diagnostic.diagnose(onA);

    String text = serialized(d);
    assertThat(text).doesNotContain(coreB.getName()).doesNotContain(downB.getName());
    assertThat(sources(d)).doesNotContain(CauseSource.AMONT_EN_ALERTE);
    assertThat(d.alertesVoisines()).noneSatisfy(v -> assertThat(v.id()).isEqualTo(rootB));
    assertThat(d.evenementsRecents()).isEmpty();
    assertThat(d.changementsRecents()).isEmpty();
    assertThat(d.impact().equipementsEnAval()).isEqualTo(1);
    assertThat(d.impact().equipements())
        .extracting(DiagnosticResponse.EquipementTouche::nom)
        .containsExactly(downA.getName());
    assertThat(d.impact().sites()).containsExactly(siteA.getName());
    // L'alerte racine est hors périmètre : ni son identifiant ni sa raison (qui la nomme).
    assertThat(d.impact().racine()).isFalse();
    assertThat(d.impact().alerteRacineId()).isNull();
    assertThat(d.impact().raisonRegroupement()).doesNotContain(coreB.getName());

    assertThatThrownBy(() -> diagnostic.diagnose(alertOnB))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    assertThatThrownBy(() -> diagnostic.diagnose(UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ------------------------------------------------------------------ plafonds et cas limites

  @Test
  void lesListesSontPlafonnees() {
    Equipment core = equipment("Cœur", EquipmentType.ROUTER, siteA);
    for (int i = 0; i < 14; i++) {
      Equipment leaf = equipment("Feuille " + i, EquipmentType.SWITCH, siteA);
      link(leaf, core);
      alert(leaf, "COLLECTION", null, t0.plusSeconds(i), "Plus de réponse");
      jdbc.update(
          "INSERT INTO monitored_service(id, equipment_id, name, type, active) VALUES (?, ?, ?,"
              + " 'ICMP', TRUE)",
          UUID.randomUUID(),
          leaf.getId(),
          "Ping " + i);
    }
    for (int i = 0; i < 25; i++) {
      event(core, "Erreur " + i, null, "ERROR", t0.minusSeconds(10L + i));
      audit("EQUIPMENT_UPDATED", "EQUIPMENT", core.getId(), t0.minusSeconds(20L + i), "x");
    }
    UUID id = alert(core, "COLLECTION", null, t0, "Plus de réponse");

    DiagnosticResponse d = diagnostic.diagnose(id);

    assertThat(d.impact().equipementsEnAval()).isEqualTo(14);
    assertThat(d.impact().equipements()).hasSize(10);
    assertThat(d.impact().servicesTouches()).isEqualTo(14);
    assertThat(d.impact().services()).hasSize(10);
    assertThat(d.alertesVoisines()).hasSize(10);
    assertThat(d.evenementsRecents()).hasSize(10);
    assertThat(d.changementsRecents()).hasSize(10);
    assertThat(d.causesProbables()).hasSizeLessThanOrEqualTo(5);
    assertThat(d.pistes()).hasSizeBetween(2, 5);
  }

  @Test
  void sansAucuneDonneeLeDiagnosticEstVideEtLeResumeNInventeRien() {
    Equipment lonely = equipment("Serveur isolé", EquipmentType.SERVER, siteA);
    AlertRule cpu = rule("cpu.usage");
    UUID id = alert(lonely, "THRESHOLD", cpu.getId(), t0, "CPU haut");

    DiagnosticResponse d = diagnostic.diagnose(id);

    assertThat(d.alerteId()).isEqualTo(id);
    assertThat(d.ouverture()).isEqualTo(t0);
    assertThat(d.fenetreMinutes()).isEqualTo(30);
    assertThat(d.causesProbables()).isEmpty();
    assertThat(d.alertesVoisines()).isEmpty();
    assertThat(d.evenementsRecents()).isEmpty();
    assertThat(d.changementsRecents()).isEmpty();
    assertThat(d.impact().racine()).isTrue();
    assertThat(d.impact().equipementsEnAval()).isZero();
    assertThat(d.impact().equipements()).isEmpty();
    assertThat(d.impact().sites()).containsExactly(siteA.getName());
    assertThat(d.incidentsSimilaires().nombre()).isZero();
    assertThat(d.incidentsSimilaires().medianeMinutes()).isNull();
    assertThat(d.incidentsSimilaires().dernieres()).isEmpty();
    assertThat(d.contexteMetrique()).isNotNull();
    assertThat(d.contexteMetrique().dernieresValeurs()).isEmpty();
    assertThat(d.contexteMetrique().moyenneReference()).isNull();
    assertThat(d.contexteMetrique().ecartPourcent()).isNull();
    assertThat(d.pistes()).hasSizeBetween(2, 5);
    assertThat(d.resume())
        .contains(lonely.getName())
        .contains(cpu.getName())
        .contains("Aucune cause probable identifiée")
        .doesNotContain("aval")
        .doesNotContain("Cause probable :")
        .doesNotContain("incident")
        .doesNotContain("regroupée");
  }

  @Test
  void leContexteMetriqueCompareALaMoyenneDesSeptJoursPrecedentsALaMemeHeure() {
    Equipment server = equipment("Serveur mesures", EquipmentType.SERVER, siteA);
    AlertRule cpu = rule("cpu.usage");
    for (int day = 1; day <= 7; day++) {
      metric(server, "cpu.usage", 40, t0.minus(Duration.ofDays(day)));
    }
    metric(server, "cpu.usage", 60, t0.minusSeconds(120));
    metric(server, "cpu.usage", 95, t0);
    UUID id = alert(server, "THRESHOLD", cpu.getId(), t0, "CPU haut");

    var context = diagnostic.diagnose(id).contexteMetrique();

    assertThat(context.metrique()).isEqualTo("cpu.usage");
    assertThat(context.seuil()).isEqualTo(90.0);
    assertThat(context.operateur()).isEqualTo("GREATER_THAN");
    assertThat(context.valeurOuverture()).isEqualTo(95.0);
    assertThat(context.moyenneReference()).isEqualTo(40.0);
    assertThat(context.joursReference()).isEqualTo(7);
    assertThat(context.ecartPourcent()).isEqualTo(137.5);
    assertThat(context.dernieresValeurs()).isNotEmpty().hasSizeLessThanOrEqualTo(10);
    assertThat(context.dernieresValeurs().get(context.dernieresValeurs().size() - 1).valeur())
        .isEqualTo(95.0);
  }

  @Test
  void sansAssezDHistoriqueLaMoyenneDeReferenceResteNulle() {
    Equipment server = equipment("Serveur neuf", EquipmentType.SERVER, siteA);
    AlertRule cpu = rule("cpu.usage");
    metric(server, "cpu.usage", 40, t0.minus(Duration.ofDays(1)));
    metric(server, "cpu.usage", 40, t0.minus(Duration.ofDays(2)));
    metric(server, "cpu.usage", 95, t0);
    UUID id = alert(server, "THRESHOLD", cpu.getId(), t0, "CPU haut");

    var context = diagnostic.diagnose(id).contexteMetrique();

    assertThat(context.moyenneReference()).isNull();
    assertThat(context.ecartPourcent()).isNull();
    assertThat(context.joursReference()).isEqualTo(2);
  }

  @Test
  void lesIncidentsSimilairesDonnentMedianeMoyenneEtDernieresResolutions() {
    Equipment server = equipment("Serveur récidiviste", EquipmentType.SERVER, siteA);
    AlertRule cpu = rule("cpu.usage");
    int[] minutes = {10, 20, 60};
    for (int i = 0; i < minutes.length; i++) {
      Instant start = t0.minus(Duration.ofDays(10L - i));
      Instant end = start.plus(Duration.ofMinutes(minutes[i]));
      UUID past =
          alert(server, "THRESHOLD", cpu.getId(), start, end, "CPU haut", null, null, "past:");
      resolvedEvent(past, end, "Redémarrage du service n°" + i + "\u0007");
    }
    // Trop ancien (plus de 90 jours) : ignoré.
    alert(
        server,
        "THRESHOLD",
        cpu.getId(),
        t0.minus(Duration.ofDays(120)),
        t0.minus(Duration.ofDays(120)).plusSeconds(60),
        "CPU haut",
        null,
        null,
        "old:");
    UUID id = alert(server, "THRESHOLD", cpu.getId(), t0, "CPU haut");

    var similar = diagnostic.diagnose(id).incidentsSimilaires();

    assertThat(similar.critere()).isEqualTo("MEME_EQUIPEMENT");
    assertThat(similar.nombre()).isEqualTo(3);
    assertThat(similar.resolus()).isEqualTo(3);
    assertThat(similar.medianeMinutes()).isEqualTo(20.0);
    assertThat(similar.moyenneMinutes()).isEqualTo(30.0);
    assertThat(similar.dernieres()).hasSize(3);
    assertThat(similar.dernieres().get(0).commentaire()).isEqualTo("Redémarrage du service n°2");
    assertThat(similar.dernieres().get(0).dureeMinutes()).isEqualTo(60);

    // Rien sur cet équipement : même règle sur le même site.
    Equipment neighbour = equipment("Voisin", EquipmentType.SERVER, siteA);
    UUID other = alert(neighbour, "THRESHOLD", cpu.getId(), t0.plusSeconds(5), "CPU haut");
    var bySite = diagnostic.diagnose(other).incidentsSimilaires();
    assertThat(bySite.critere()).isEqualTo("MEME_SITE");
    assertThat(bySite.nombre()).isEqualTo(4);
  }

  @Test
  void leTexteDesEvenementsEstDebarrasseDesCaracteresDeControle() {
    Equipment router = equipment("Routeur bavard", EquipmentType.ROUTER, siteA);
    event(router, "Erreur\u0007 disque\n\rforgée", null, "ERROR", t0.minusSeconds(30));
    UUID id = alert(router, "COLLECTION", null, t0, "Plus de réponse");

    var events = diagnostic.diagnose(id).evenementsRecents();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).titre()).isEqualTo("Erreur disque forgée");
  }

  @Test
  void uneAlerteRouverteSeDiagnostiqueASaDerniereOuverture() {
    Equipment router = equipment("Routeur rouvert", EquipmentType.ROUTER, siteA);
    UUID id = alert(router, "COLLECTION", null, t0.minus(Duration.ofHours(5)), "Plus de réponse");
    jdbc.update(
        "INSERT INTO alert_event(id, alert_id, type, new_state, occurred_at)"
            + " VALUES (?, ?, 'OPENED', 'OPEN', ?)",
        UUID.randomUUID(),
        id,
        Timestamp.from(t0));
    event(router, "linkDown (interface coupée)", LINK_DOWN, "ERROR", t0.minusSeconds(60));

    DiagnosticResponse d = diagnostic.diagnose(id);

    assertThat(d.ouverture()).isEqualTo(t0);
    assertThat(first(d).source()).isEqualTo(CauseSource.EVENEMENT_RECENT);
  }

  /** Toute la réponse en texte (un record imprime chacun de ses champs, listes comprises). */
  private static String serialized(DiagnosticResponse d) {
    return d.toString();
  }

  @Test
  void lesIndicateursQuiOntBougeEnMemeTempsSontListesDuPlusGrandChangementAuPlusPetit() {
    Equipment server = equipment("Serveur applicatif", EquipmentType.SERVER, siteA);
    // Deux heures de calme, puis la mémoire double et le trafic s'effondre à l'ouverture.
    for (int minutes = 120; minutes > 15; minutes -= 5) {
      metric(server, "memory.usage", 30, t0.minus(Duration.ofMinutes(minutes)));
      metric(server, "net.in", 100, t0.minus(Duration.ofMinutes(minutes)));
      metric(server, "cpu.usage", 50, t0.minus(Duration.ofMinutes(minutes)));
    }
    for (int minutes = 12; minutes >= 0; minutes -= 4) {
      metric(server, "memory.usage", 75, t0.minus(Duration.ofMinutes(minutes)));
      metric(server, "net.in", 20, t0.minus(Duration.ofMinutes(minutes)));
      metric(server, "cpu.usage", 51, t0.minus(Duration.ofMinutes(minutes)));
    }
    AlertRule cpu = rule("cpu.usage");
    UUID threshold = alert(server, "THRESHOLD", cpu.getId(), t0, "Processeur haut");

    DiagnosticResponse d = diagnostic.diagnose(threshold);

    assertThat(d.metriquesLiees())
        .extracting(m -> m.metrique())
        .containsExactly("memory.usage", "net.in");
    // La mémoire a plus bougé (+150 %) que le trafic (−80 %) : elle passe devant.
    var memory = d.metriquesLiees().get(0);
    assertThat(memory.avant()).isEqualTo(30.0);
    assertThat(memory.pendant()).isEqualTo(75.0);
    assertThat(memory.variationPourcent()).isEqualTo(150.0);
    assertThat(d.metriquesLiees().get(1).variationPourcent()).isEqualTo(-80.0);
    // L'indicateur de l'alerte elle-même n'est pas répété, et le calme n'est pas listé.
    assertThat(d.metriquesLiees()).extracting(m -> m.metrique()).doesNotContain("cpu.usage");
  }
}
