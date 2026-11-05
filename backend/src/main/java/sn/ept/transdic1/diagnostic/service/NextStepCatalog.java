package sn.ept.transdic1.diagnostic.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Catalogue fixe des pistes de vérification, choisies selon les deux premières causes puis le type
 * d'alerte. Les phrases sont écrites ici, jamais générées : elles sont relues comme du code.
 */
@Component
@Order(100)
public class NextStepCatalog implements NextStepProvider {
  static final String UPSTREAM_FIRST =
      "Traiter d'abord l'alerte de l'équipement amont : les alertes en aval se résoudront avec elle.";
  static final String UPSTREAM_LINK =
      "Vérifier le lien entre l'équipement amont et celui-ci (câble, port, alimentation).";
  static final String LINK_DOWN =
      "Vérifier l'alimentation et le câble du port signalé par le trap linkDown.";
  static final String LINK_PEER =
      "Contrôler l'état du port en face, sur le commutateur ou le routeur voisin.";
  static final String RESTART =
      "Chercher la cause du redémarrage : alimentation, onduleur, journal système.";
  static final String AUTH =
      "Vérifier que la communauté ou les identifiants SNMP sont les mêmes des deux côtés.";
  static final String LOG = "Lire le journal de l'équipement autour de l'heure du message signalé.";
  static final String CHANGE_REVIEW =
      "Revoir le changement signalé juste avant l'alerte et l'annuler s'il est en cause.";
  static final String CHANGE_AUDIT =
      "Consulter le journal d'audit pour le détail du changement (administrateurs).";
  static final String AGENT =
      "Vérifier que le service de l'agent tourne sur la machine et que son jeton est valide.";
  static final String SNMP =
      "Vérifier la configuration SNMP (version, communauté ou identifiants) et le pare-feu (UDP"
          + " 161).";
  static final String ICMP =
      "Vérifier la joignabilité réseau de l'équipement (ping) depuis le serveur de supervision.";
  static final String POWER = "Vérifier que l'équipement est allumé et raccordé au réseau.";
  static final String DISK = "Nettoyer ou étendre le disque avant la date de saturation prévue.";
  static final String MEMORY =
      "Identifier le processus qui consomme la mémoire et prévoir un ajout de mémoire.";
  static final String CAPACITY = "Prévoir une extension de capacité avant la saturation prévue.";
  static final String CPU = "Identifier les processus qui consomment le plus de processeur.";
  static final String NETWORK = "Vérifier la charge et la qualité du lien réseau de l'équipement.";
  static final String ANOMALY =
      "Comparer avec le même créneau des semaines précédentes dans l'historique des mesures.";
  static final String EVENT = "Lire le détail de l'événement réseau qui a ouvert l'alerte.";
  static final String HISTORY =
      "Consulter l'historique des mesures de l'équipement autour de l'heure d'ouverture.";
  static final String DOCUMENT =
      "Noter la résolution en commentaire : elle servira aux prochains incidents similaires.";

  @Override
  public List<String> steps(Context context) {
    var steps = new ArrayList<String>();
    context.causes().stream()
        .limit(2)
        .forEach(scored -> steps.addAll(forCause(scored.candidate())));
    steps.addAll(forAlertType(context));
    steps.add(HISTORY);
    steps.add(DOCUMENT);
    return steps;
  }

  private static List<String> forCause(CauseCandidate cause) {
    String nature = cause.nature() == null ? "" : cause.nature();
    return switch (cause.source()) {
      case AMONT_EN_ALERTE -> List.of(UPSTREAM_FIRST, UPSTREAM_LINK);
      case EVENEMENT_RECENT ->
          switch (nature) {
            case "LINK_DOWN" -> List.of(LINK_DOWN, LINK_PEER);
            case "RESTART" -> List.of(RESTART);
            case "AUTH_FAILURE" -> List.of(AUTH);
            default -> List.of(LOG);
          };
      case CHANGEMENT_RECENT -> List.of(CHANGE_REVIEW, CHANGE_AUDIT);
      case SILENCE_COLLECTE -> List.of(forMode(nature));
      case TENDANCE_SATURATION -> List.of(forMetric(nature, CAPACITY));
      case ANOMALIE -> List.of(ANOMALY);
    };
  }

  private static List<String> forAlertType(Context context) {
    String type = context.alertType() == null ? "" : context.alertType();
    return switch (type) {
      case "COLLECTION" -> List.of(forMode(context.collectionMode()));
      case "THRESHOLD", "ANOMALY", "FORECAST" -> List.of(forMetric(context.metricKey(), HISTORY));
      case "EVENT" -> List.of(EVENT);
      default -> List.of();
    };
  }

  private static String forMode(String mode) {
    return switch (mode == null ? "" : mode) {
      case "AGENT" -> AGENT;
      case "SNMP" -> SNMP;
      case "ICMP" -> ICMP;
      default -> POWER;
    };
  }

  private static String forMetric(String key, String fallback) {
    String k = key == null ? "" : key;
    if (k.startsWith("disk")) {
      return DISK;
    }
    if (k.startsWith("memory")) {
      return MEMORY;
    }
    if (k.startsWith("cpu")) {
      return CPU;
    }
    if (k.startsWith("network") || k.startsWith("latency") || k.startsWith("availability")) {
      return NETWORK;
    }
    return fallback;
  }
}
