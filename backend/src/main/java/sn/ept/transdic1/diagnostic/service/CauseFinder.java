package sn.ept.transdic1.diagnostic.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import sn.ept.transdic1.alerting.dto.CapacityForecastResponse;
import sn.ept.transdic1.diagnostic.domain.CauseSource;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.AlertFacts;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.ChangeRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.EventRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.OwnAlertRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.UpstreamAlertRow;
import sn.ept.transdic1.diagnostic.service.CauseScoring.Strength;

/**
 * Règles de détection des causes candidates (ADR 0024). Chaque règle ne produit une cause que si un
 * fait la justifie, et chaque cause porte ses preuves en clair. Les forces viennent de {@link
 * CauseScoring.Strength} ; le score et le classement, de {@link CauseScoring}.
 */
final class CauseFinder {
  /** Une preuve postérieure à l'ouverture n'explique pas l'alerte, sauf décalage de détection. */
  static final Duration TOLERANCE = Duration.ofMinutes(1);

  private static final String STANDARD_TRAP = "1.3.6.1.6.3.1.1.5.";

  /** Faits lus en base pour une alerte, déjà filtrés par le périmètre de l'utilisateur. */
  record Evidence(
      List<UpstreamAlertRow> upstreamAlerts,
      List<OwnAlertRow> ownAlerts,
      List<EventRow> events,
      List<ChangeRow> changes,
      String metricKey,
      CapacityForecastResponse forecast) {}

  /** Natures d'événement, de la plus explicative à la moins explicative. */
  enum EventKind {
    LINK_DOWN,
    RESTART,
    AUTH_FAILURE,
    CRITICAL_MESSAGE,
    ERROR_MESSAGE
  }

  /** Mode de collecte déduit du message de l'alerte de collecte. */
  enum CollectionMode {
    AGENT,
    SNMP,
    ICMP,
    INCONNU
  }

  private final AlertFacts alert;
  private final DiagnosticTexts texts;
  private final Duration window;

  CauseFinder(AlertFacts alert, DiagnosticTexts texts, Duration window) {
    this.alert = alert;
    this.texts = texts;
    this.window = window;
  }

  List<CauseCandidate> find(Evidence evidence) {
    var candidates = new ArrayList<CauseCandidate>();
    upstream(evidence.upstreamAlerts(), candidates);
    events(evidence.events(), candidates);
    changes(evidence.changes(), candidates);
    trend(evidence, candidates);
    anomalies(evidence, candidates);
    silence(evidence.ownAlerts(), candidates);
    return candidates;
  }

  // ------------------------------------------------------------------ amont

  private void upstream(List<UpstreamAlertRow> rows, List<CauseCandidate> out) {
    // Une cause par équipement amont : la première ligne (collecte d'abord, plus éloigné d'abord).
    var byEquipment = new LinkedHashMap<UUID, UpstreamAlertRow>();
    rows.forEach(row -> byEquipment.putIfAbsent(row.equipmentId(), row));
    for (UpstreamAlertRow row : byEquipment.values()) {
      boolean collection = "COLLECTION".equals(row.type());
      String name = DiagnosticTexts.clean(row.equipmentName(), 120);
      var reasons = new ArrayList<String>();
      reasons.add(
          name
              + " est en amont de "
              + DiagnosticTexts.clean(alert.equipmentName(), 120)
              + " dans la topologie (distance "
              + row.depth()
              + ").");
      reasons.add(
          "Alerte "
              + DiagnosticTexts.alertType(row.type())
              + " ouverte sur "
              + name
              + " à "
              + texts.time(row.triggeredAt())
              + " : « "
              + DiagnosticTexts.clean(row.message(), 200)
              + " ».");
      if (row.id().equals(alert.parentAlertId())) {
        reasons.add("Le regroupement des alertes a rattaché cette alerte à la sienne.");
      }
      out.add(
          new CauseCandidate(
              CauseSource.AMONT_EN_ALERTE,
              collection ? Strength.UPSTREAM_COLLECTION : Strength.UPSTREAM_OTHER,
              row.triggeredAt(),
              collection
                  ? name + " (en amont) est injoignable"
                  : "Alerte en cours sur " + name + " (en amont)",
              reasons,
              row.equipmentId(),
              row.id(),
              collection ? "COLLECTION" : row.type()));
    }
  }

  // ------------------------------------------------------------------ événements

  private void events(List<EventRow> rows, List<CauseCandidate> out) {
    Instant from = alert.openedAt().minus(window);
    Instant until = alert.openedAt().plus(TOLERANCE);
    // Une cause par (équipement, nature) : l'événement le plus récent avant l'ouverture.
    record Key(UUID equipment, EventKind kind) {}
    var latest = new LinkedHashMap<Key, EventRow>();
    var counts = new LinkedHashMap<Key, Integer>();
    for (EventRow row : rows) {
      EventKind kind = kind(row);
      if (kind == null || row.receivedAt().isBefore(from) || row.receivedAt().isAfter(until)) {
        continue;
      }
      var key = new Key(row.equipmentId(), kind);
      // Lignes triées du plus récent au plus ancien : la première vue est la plus récente.
      latest.putIfAbsent(key, row);
      counts.merge(key, 1, Integer::sum);
    }
    for (Map.Entry<Key, EventRow> entry : latest.entrySet()) {
      EventRow row = entry.getValue();
      EventKind kind = entry.getKey().kind();
      String name = DiagnosticTexts.clean(row.equipmentName(), 120);
      String title = DiagnosticTexts.clean(row.title(), 160);
      var reasons = new ArrayList<String>();
      reasons.add(
          DiagnosticTexts.sourceLabel(row.source())
              + " « "
              + title
              + " » reçu de "
              + name
              + " à "
              + texts.time(row.receivedAt())
              + timing(row.receivedAt())
              + ".");
      int count = counts.get(entry.getKey());
      if (count > 1) {
        reasons.add(count + " événements de cette nature dans la fenêtre d'analyse.");
      }
      if (row.upstream()) {
        reasons.add(
            name + " est en amont de " + DiagnosticTexts.clean(alert.equipmentName(), 120) + ".");
      }
      double strength = strength(kind) * (row.upstream() ? Strength.UPSTREAM_FACTOR : 1.0);
      out.add(
          new CauseCandidate(
              CauseSource.EVENEMENT_RECENT,
              strength,
              row.receivedAt(),
              eventLabel(kind, name, title),
              reasons,
              row.equipmentId(),
              null,
              kind.name()));
    }
  }

  static EventKind kind(EventRow row) {
    String oid = row.trapOid() == null ? "" : row.trapOid();
    String title = row.title() == null ? "" : row.title().toLowerCase(Locale.ROOT);
    if (oid.equals(STANDARD_TRAP + "3") || title.contains("linkdown")) {
      return EventKind.LINK_DOWN;
    }
    if (oid.equals(STANDARD_TRAP + "1")
        || oid.equals(STANDARD_TRAP + "2")
        || title.contains("coldstart")
        || title.contains("warmstart")) {
      return EventKind.RESTART;
    }
    if (oid.equals(STANDARD_TRAP + "5")
        || title.contains("authenticationfailure")
        || title.contains("authfailure")) {
      return EventKind.AUTH_FAILURE;
    }
    return switch (row.severity() == null ? "" : row.severity()) {
      case "EMERGENCY", "ALERT", "CRITICAL" -> EventKind.CRITICAL_MESSAGE;
      case "ERROR" -> EventKind.ERROR_MESSAGE;
      default -> null;
    };
  }

  private static double strength(EventKind kind) {
    return switch (kind) {
      case LINK_DOWN -> Strength.LINK_DOWN;
      case RESTART -> Strength.RESTART;
      case AUTH_FAILURE -> Strength.AUTH_FAILURE;
      case CRITICAL_MESSAGE -> Strength.CRITICAL_MESSAGE;
      case ERROR_MESSAGE -> Strength.ERROR_MESSAGE;
    };
  }

  private static String eventLabel(EventKind kind, String name, String title) {
    return switch (kind) {
      case LINK_DOWN -> "Interface coupée sur " + name + " (trap linkDown)";
      case RESTART -> "Redémarrage de " + name + " (" + title + ")";
      case AUTH_FAILURE -> "Échec d'authentification SNMP sur " + name;
      case CRITICAL_MESSAGE -> "Message critique de " + name + " : " + title;
      case ERROR_MESSAGE -> "Erreur signalée par " + name + " : " + title;
    };
  }

  // ------------------------------------------------------------------ changements

  private void changes(List<ChangeRow> rows, List<CauseCandidate> out) {
    Instant from = alert.openedAt().minus(window);
    Instant until = alert.openedAt().plus(TOLERANCE);
    for (ChangeRow row : rows) {
      Double base = CauseScoring.CHANGE_STRENGTH.get(row.action());
      if (base == null || row.performedAt().isBefore(from) || row.performedAt().isAfter(until)) {
        continue;
      }
      String label = DiagnosticTexts.changeLabel(row.action());
      String target = DiagnosticTexts.target(row.targetType(), row.targetName());
      String author = row.username() == null ? "un traitement automatique" : row.username();
      var reasons = new ArrayList<String>();
      reasons.add(
          label
              + " par "
              + DiagnosticTexts.clean(author, 64)
              + " à "
              + texts.time(row.performedAt())
              + timing(row.performedAt())
              + ".");
      if ("ALERT_RULE".equals(row.targetType()) || "EVENT_RULE".equals(row.targetType())) {
        reasons.add("C'est la règle qui a ouvert cette alerte.");
      }
      if (row.upstreamEquipment()) {
        reasons.add("L'équipement modifié est en amont dans la topologie.");
      }
      reasons.add("Le détail des valeurs reste dans le journal d'audit (administrateurs).");
      out.add(
          new CauseCandidate(
              CauseSource.CHANGEMENT_RECENT,
              base * (row.upstreamEquipment() ? Strength.UPSTREAM_FACTOR : 1.0),
              row.performedAt(),
              label + " : " + target,
              reasons,
              "EQUIPMENT".equals(row.targetType()) ? row.targetId() : null,
              null,
              row.action()));
    }
  }

  // ------------------------------------------------------------------ tendance, anomalie

  private void trend(Evidence evidence, List<CauseCandidate> out) {
    if ("FORECAST".equals(alert.type())) {
      var reasons = new ArrayList<String>();
      reasons.add(
          "Prévision ouverte à "
              + texts.time(alert.openedAt())
              + " : « "
              + DiagnosticTexts.clean(alert.message(), 200)
              + " ».");
      CapacityForecastResponse f = evidence.forecast();
      if (f != null && f.slopePerDay() != null && f.r2() != null) {
        reasons.add(
            String.format(
                Locale.FRANCE,
                "Tendance actuelle %+.2f %%/jour sur %d points (r² %.2f, confiance %s).",
                f.slopePerDay(),
                f.points(),
                f.r2(),
                f.confidence()));
      }
      out.add(
          new CauseCandidate(
              CauseSource.TENDANCE_SATURATION,
              Strength.FORECAST_SELF,
              alert.openedAt(),
              "Tendance de saturation : " + DiagnosticTexts.clean(alert.message(), 160),
              reasons,
              alert.equipmentId(),
              null,
              evidence.metricKey()));
    }
    for (OwnAlertRow row : evidence.ownAlerts()) {
      if (!"FORECAST".equals(row.type())) {
        continue;
      }
      String key = MetricKeys.of(row.type(), row.occurrenceKey(), alert.equipmentId(), null);
      boolean sameMetric = sameFamily(key, evidence.metricKey());
      out.add(
          new CauseCandidate(
              CauseSource.TENDANCE_SATURATION,
              sameMetric ? Strength.FORECAST_SAME_METRIC : Strength.FORECAST_OTHER_METRIC,
              row.triggeredAt(),
              "Saturation prévue : " + DiagnosticTexts.clean(row.message(), 160),
              List.of(
                  "Alerte de prévision active depuis " + texts.time(row.triggeredAt()) + ".",
                  sameMetric
                      ? "Elle porte sur le même indicateur que cette alerte."
                      : "Elle porte sur un autre indicateur de l'équipement."),
              alert.equipmentId(),
              row.id(),
              key));
    }
  }

  private void anomalies(Evidence evidence, List<CauseCandidate> out) {
    Instant until = alert.openedAt().plus(TOLERANCE);
    for (OwnAlertRow row : evidence.ownAlerts()) {
      if (!"ANOMALY".equals(row.type()) || row.triggeredAt().isAfter(until)) {
        continue;
      }
      String key = MetricKeys.of(row.type(), row.occurrenceKey(), alert.equipmentId(), null);
      boolean sameMetric = key != null && key.equals(evidence.metricKey());
      out.add(
          new CauseCandidate(
              CauseSource.ANOMALIE,
              sameMetric ? Strength.ANOMALY_SAME_METRIC : Strength.ANOMALY_OTHER_METRIC,
              row.triggeredAt(),
              "Comportement inhabituel" + (key == null ? "" : " de " + key),
              List.of(
                  "Alerte d'anomalie ouverte à "
                      + texts.time(row.triggeredAt())
                      + timing(row.triggeredAt())
                      + " : « "
                      + DiagnosticTexts.clean(row.message(), 200)
                      + " »."),
              alert.equipmentId(),
              row.id(),
              key));
    }
  }

  // ------------------------------------------------------------------ silence de collecte

  private void silence(List<OwnAlertRow> own, List<CauseCandidate> out) {
    if ("COLLECTION".equals(alert.type())) {
      CollectionMode mode = mode(alert.message());
      out.add(
          new CauseCandidate(
              CauseSource.SILENCE_COLLECTE,
              Strength.SILENCE_SELF,
              alert.openedAt(),
              silenceLabel(mode),
              List.of(
                  "Message de l'alerte : « " + DiagnosticTexts.clean(alert.message(), 200) + " ».",
                  "Plus aucune mesure n'arrive depuis " + texts.time(alert.openedAt()) + "."),
              alert.equipmentId(),
              null,
              mode.name()));
      return;
    }
    Instant until = alert.openedAt().plus(TOLERANCE);
    for (OwnAlertRow row : own) {
      if (!"COLLECTION".equals(row.type()) || row.triggeredAt().isAfter(until)) {
        continue;
      }
      CollectionMode mode = mode(row.message());
      out.add(
          new CauseCandidate(
              CauseSource.SILENCE_COLLECTE,
              Strength.SILENCE_OTHER,
              row.triggeredAt(),
              silenceLabel(mode),
              List.of(
                  "Alerte de collecte active depuis "
                      + texts.time(row.triggeredAt())
                      + " : « "
                      + DiagnosticTexts.clean(row.message(), 200)
                      + " ».",
                  "Les mesures de l'équipement manquent ou sont incomplètes."),
              alert.equipmentId(),
              row.id(),
              mode.name()));
    }
  }

  static CollectionMode mode(String message) {
    String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
    if (text.contains("agent")) {
      return CollectionMode.AGENT;
    }
    if (text.contains("snmp")) {
      return CollectionMode.SNMP;
    }
    if (text.contains("injoignable")) {
      return CollectionMode.ICMP;
    }
    return CollectionMode.INCONNU;
  }

  private static String silenceLabel(CollectionMode mode) {
    return switch (mode) {
      case AGENT -> "L'agent de collecte ne communique plus";
      case SNMP -> "L'équipement ne répond plus en SNMP";
      case ICMP -> "L'équipement ne répond plus au ping (ICMP)";
      case INCONNU -> "Aucune mesure ne remonte de l'équipement";
    };
  }

  // ------------------------------------------------------------------ outils

  /** « , 3 min avant l'ouverture » ou « , à l'ouverture ». */
  private String timing(Instant at) {
    long minutes = DiagnosticTexts.minutesBetween(at, alert.openedAt());
    if (!at.isBefore(alert.openedAt()) || minutes == 0) {
      return ", au moment de l'ouverture";
    }
    return ", " + minutes + " min avant l'ouverture";
  }

  /** Même famille d'indicateur : « disk.usage » et « disk.usage./var ». */
  static boolean sameFamily(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return a.equals(b) || a.startsWith(b + ".") || b.startsWith(a + ".");
  }
}
