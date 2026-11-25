package sn.ept.transdic1.impact.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calculs purs de l'analyse d'impact, sans base : second chemin, niveau d'impact, phrase de
 * synthèse et recommandations. Vérifiables sur des topologies écrites à la main.
 */
public final class ImpactAssessment {
  public static final String LOW = "FAIBLE";
  public static final String MEDIUM = "MOYEN";
  public static final String HIGH = "ELEVE";
  public static final String CRITICAL = "CRITIQUE";

  private ImpactAssessment() {}

  /**
   * Équipements touchés qui restent joignables sans passer par la cible : ceux qui ont un amont
   * hors de la zone touchée, et, de proche en proche, ceux qui ont un amont lui-même joignable.
   * Point fixe : on répète tant qu'un équipement change d'état (au plus une fois par équipement).
   *
   * @param touched la cible et tout son aval
   * @param target l'équipement arrêté
   * @param upstreams amonts de chaque équipement touché
   */
  public static Set<UUID> redundant(
      Set<UUID> touched, UUID target, Map<UUID, List<UUID>> upstreams) {
    Set<UUID> reachable = new HashSet<>();
    boolean changed = true;
    while (changed) {
      changed = false;
      for (UUID node : touched) {
        if (node.equals(target) || reachable.contains(node)) {
          continue;
        }
        for (UUID up : upstreams.getOrDefault(node, List.of())) {
          if (up.equals(target)) {
            continue;
          }
          if (!touched.contains(up) || reachable.contains(up)) {
            reachable.add(node);
            changed = true;
            break;
          }
        }
      }
    }
    return reachable;
  }

  public static String level(
      int lostDownstream, int services, int components, long publicComponents) {
    if (publicComponents > 0 || lostDownstream >= 10) {
      return CRITICAL;
    }
    if (lostDownstream >= 3 || components > 0) {
      return HIGH;
    }
    if (lostDownstream >= 1 || services > 0) {
      return MEDIUM;
    }
    return LOW;
  }

  public static String summary(
      String name,
      int lostDownstream,
      int redundant,
      int services,
      int components,
      long publicComponents) {
    if (lostDownstream == 0 && services == 0 && components == 0) {
      return "Arrêter "
          + name
          + " ne touche aucun autre équipement connu de la topologie"
          + (redundant > 0
              ? " ("
                  + plural(redundant, "équipement reste", "équipements restent")
                  + " joignable"
                  + (redundant > 1 ? "s" : "")
                  + " par un second chemin)."
              : ".");
    }
    List<String> parts = new ArrayList<>();
    if (lostDownstream > 0) {
      parts.add(
          "rend injoignable"
              + (lostDownstream > 1 ? "s " : " ")
              + plural(lostDownstream, "équipement", "équipements")
              + " en aval");
    }
    if (services > 0) {
      parts.add("interrompt " + plural(services, "service surveillé", "services surveillés"));
    }
    if (components > 0) {
      parts.add(
          "touche "
              + plural(components, "composant de page de statut", "composants de pages de statut")
              + (publicComponents > 0
                  ? ", dont "
                      + publicComponents
                      + " visible"
                      + (publicComponents > 1 ? "s" : "")
                      + " des usagers"
                  : ""));
    }
    String sentence = "Arrêter " + name + " " + join(parts) + ".";
    if (redundant > 0) {
      sentence +=
          " "
              + capitalize(
                  plural(redundant, "autre équipement reste", "autres équipements restent"))
              + " joignable"
              + (redundant > 1 ? "s" : "")
              + " par un second chemin.";
    }
    return sentence;
  }

  public static List<String> recommendations(
      int lostDownstream,
      List<String> publicComponents,
      List<String> learnedLinks,
      String onCallPrimary,
      boolean maintenancePlanned,
      int activeAlerts) {
    List<String> advice = new ArrayList<>();
    if (lostDownstream > 0) {
      advice.add(
          "Planifier la maintenance sur l'équipement et ses "
              + plural(lostDownstream, "équipement", "équipements")
              + " en aval : leurs alertes seront tracées sans réveiller personne.");
    } else {
      advice.add(
          "Planifier une maintenance sur l'équipement : ses alertes seront tracées sans"
              + " notification.");
    }
    if (!publicComponents.isEmpty()) {
      advice.add(
          "Prévenir les usagers sur la page de statut avant l'intervention ("
              + String.join(", ", publicComponents)
              + ").");
    }
    if (!learnedLinks.isEmpty()) {
      advice.add(
          "Surveiller aussi "
              + String.join(", ", learnedLinks)
              + " : l'historique montre qu'ils tombent souvent en même temps.");
    }
    if (onCallPrimary != null) {
      advice.add("Prévenir la personne d'astreinte du site : " + onCallPrimary + ".");
    }
    if (activeAlerts > 0) {
      advice.add(
          plural(activeAlerts, "alerte est déjà ouverte", "alertes sont déjà ouvertes")
              + " sur ces équipements : les traiter ou les noter avant d'intervenir.");
    }
    if (maintenancePlanned) {
      advice.add(
          "Une maintenance est déjà prévue sur cet équipement ou son site : vérifier le créneau.");
    }
    return List.copyOf(advice);
  }

  private static String plural(int n, String one, String many) {
    return n + " " + (n > 1 ? many : one);
  }

  private static String join(List<String> parts) {
    if (parts.size() == 1) {
      return parts.get(0);
    }
    return String.join(", ", parts.subList(0, parts.size() - 1))
        + " et "
        + parts.get(parts.size() - 1);
  }

  private static String capitalize(String text) {
    return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }
}
