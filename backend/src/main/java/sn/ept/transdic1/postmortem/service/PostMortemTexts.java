package sn.ept.transdic1.postmortem.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Libellés en français partagés par le post-mortem, son PDF et les indicateurs de service. */
public final class PostMortemTexts {
  public static final ZoneId ZONE = ZoneId.of("Africa/Dakar");
  public static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).withZone(ZONE);
  public static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss", Locale.FRANCE).withZone(ZONE);

  private PostMortemTexts() {}

  public static String severity(String value) {
    if (value == null) {
      return "";
    }
    return switch (value) {
      case "CRITICAL" -> "critique";
      case "WARNING" -> "avertissement";
      case "INFO" -> "information";
      default -> value.toLowerCase(Locale.ROOT);
    };
  }

  public static String channel(String value) {
    if (value == null) {
      return "";
    }
    return switch (value) {
      case "EMAIL" -> "e-mail";
      case "TEAMS" -> "Teams";
      case "SLACK" -> "Slack";
      case "TELEGRAM" -> "Telegram";
      case "WEBHOOK" -> "webhook";
      case "SYSLOG" -> "SIEM (syslog)";
      default -> value;
    };
  }

  public static String causeSource(String value) {
    if (value == null) {
      return "";
    }
    return switch (value) {
      case "AMONT_EN_ALERTE" -> "Équipement en amont en panne";
      case "EVENEMENT_RECENT" -> "Événement réseau (trap ou syslog)";
      case "CHANGEMENT_RECENT" -> "Changement récent (inventaire ou configuration)";
      case "SILENCE_COLLECTE" -> "Perte de collecte";
      case "TENDANCE_SATURATION" -> "Saturation progressive";
      case "ANOMALIE" -> "Comportement inhabituel";
      default -> value;
    };
  }

  public static String alertType(String value) {
    if (value == null) {
      return "";
    }
    return switch (value) {
      case "THRESHOLD" -> "seuil";
      case "COLLECTION" -> "perte de collecte";
      case "ANOMALY" -> "anomalie";
      case "FORECAST" -> "prévision";
      case "EVENT" -> "événement";
      case "PEER" -> "écart aux semblables";
      case "NO_DATA" -> "absence de donnée";
      default -> value.toLowerCase(Locale.ROOT);
    };
  }

  /** Minutes entre deux instants, arrondies au dixième ; nul si l'un manque. */
  public static Double minutesBetween(Instant from, Instant to) {
    if (from == null || to == null) {
      return null;
    }
    double minutes = Math.max(0, Duration.between(from, to).toMillis()) / 60_000d;
    return Math.round(minutes * 10) / 10d;
  }

  public static String duration(Double minutes) {
    return minutes == null ? "—" : PostMortemFindings.minutes(minutes);
  }

  public static String at(Instant instant) {
    return instant == null ? "—" : DATE_TIME.format(instant);
  }
}
