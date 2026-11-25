package sn.ept.transdic1.postmortem.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.Finding;

/**
 * Constats d'un post-mortem, tirés des seuls faits de l'incident. Calcul pur, sans base : chaque
 * règle se vérifie sur un cas écrit à la main.
 *
 * <p>Un constat n'est produit que si un fait le justifie, et il dit ce fait en chiffres (« prise en
 * charge en 23 min »). Trois niveaux : ce qui a bien marché ({@code POSITIVE}), ce qui mérite
 * attention ({@code WARNING}), et ce qui appelle une action ({@code ACTION}), avec une
 * recommandation. Le but n'est pas de juger les personnes, mais l'organisation : c'est l'esprit des
 * post-mortems « sans reproche ».
 */
public final class PostMortemFindings {
  public static final String POSITIVE = "POSITIVE";
  public static final String WARNING = "WARNING";
  public static final String ACTION = "ACTION";

  /**
   * Au-delà, une durée de résolution courte sans intervention fait soupçonner une règle bavarde.
   */
  static final double SHORT_AUTO_RESOLUTION_MINUTES = 5;

  /** Nombre d'incidents antérieurs sur la racine, dans la fenêtre, qui fait parler de récidive. */
  static final int RECURRENCE = 2;

  private PostMortemFindings() {}

  /** Faits de l'incident utiles aux constats. */
  public record Facts(
      boolean acknowledged,
      Double minutesToAcknowledge,
      int acknowledgeTargetMinutes,
      boolean resolved,
      Double minutesToResolve,
      boolean resolvedWithoutHuman,
      int alertCount,
      int announcingAlerts,
      int notificationsSent,
      int notificationsFailed,
      Set<String> failedChannels,
      boolean escalatedToAdmins,
      boolean secondaryOnCallCalled,
      boolean onCallUnreachable,
      String rootEquipment,
      int previousIncidents,
      int recurrenceWindowDays,
      boolean causeConfirmed,
      int publicComponents,
      int publicCommunications,
      List<String> remediationsSucceeded,
      List<String> remediationsFailed,
      boolean underMaintenance) {}

  public static List<Finding> evaluate(Facts f) {
    List<Finding> findings = new ArrayList<>();

    // --- Réaction ------------------------------------------------------------
    if (f.acknowledged() && f.minutesToAcknowledge() != null) {
      if (f.minutesToAcknowledge() <= f.acknowledgeTargetMinutes()) {
        findings.add(
            new Finding(
                POSITIVE,
                "Prise en charge en "
                    + minutes(f.minutesToAcknowledge())
                    + ", dans l'objectif de "
                    + f.acknowledgeTargetMinutes()
                    + " min.",
                null));
      } else {
        findings.add(
            new Finding(
                ACTION,
                "Prise en charge en "
                    + minutes(f.minutesToAcknowledge())
                    + ", au-delà de l'objectif de "
                    + f.acknowledgeTargetMinutes()
                    + " min.",
                "Vérifier que la bonne personne a été prévenue sur un canal qu'elle consulte"
                    + " (préférences, planning d'astreinte), et raccourcir le délai d'escalade si"
                    + " l'incident était critique."));
      }
    } else if (!f.acknowledged() && !f.resolvedWithoutHuman()) {
      findings.add(
          new Finding(
              WARNING,
              f.resolved()
                  ? "Incident résolu sans que personne ne l'ait pris en charge."
                  : "Incident toujours sans prise en charge.",
              "La prise en charge arrête l'escalade et dit à l'équipe qui s'en occupe : la faire"
                  + " systématiquement."));
    }

    if (f.resolvedWithoutHuman() && f.minutesToResolve() != null) {
      if (f.minutesToResolve() < SHORT_AUTO_RESOLUTION_MINUTES
          && f.remediationsSucceeded().isEmpty()) {
        findings.add(
            new Finding(
                WARNING,
                "Retour à la normale automatique en "
                    + minutes(f.minutesToResolve())
                    + ", sans intervention humaine.",
                "Une alerte aussi brève a peut-être réveillé quelqu'un pour rien : allonger la"
                    + " durée de confirmation de la règle, ou vérifier son seuil avec l'outil de"
                    + " test de règle."));
      } else {
        findings.add(
            new Finding(
                POSITIVE,
                "Retour à la normale constaté automatiquement par la supervision, en "
                    + minutes(f.minutesToResolve())
                    + ".",
                null));
      }
    }

    // --- Remédiation -----------------------------------------------------------
    if (!f.remediationsSucceeded().isEmpty()) {
      findings.add(
          new Finding(
              POSITIVE,
              "Action corrective réussie : " + String.join(", ", f.remediationsSucceeded()) + ".",
              null));
    }
    if (!f.remediationsFailed().isEmpty()) {
      findings.add(
          new Finding(
              ACTION,
              "Action corrective en échec : " + String.join(", ", f.remediationsFailed()) + ".",
              "Lire la sortie de l'action dans le détail de l'alerte et corriger la commande dans"
                  + " la configuration de l'agent avant le prochain incident."));
    }

    // --- Bruit et notifications ----------------------------------------------------
    int avoided = Math.max(0, f.alertCount() - Math.max(1, f.announcingAlerts()));
    if (f.alertCount() > 1 && avoided > 0) {
      findings.add(
          new Finding(
              POSITIVE,
              f.alertCount()
                  + " alertes regroupées en un seul incident : "
                  + avoided
                  + " notification"
                  + (avoided > 1 ? "s" : "")
                  + " évitée"
                  + (avoided > 1 ? "s" : "")
                  + ".",
              null));
    }
    if (f.notificationsFailed() > 0) {
      findings.add(
          new Finding(
              ACTION,
              f.notificationsFailed()
                  + " notification"
                  + (f.notificationsFailed() > 1 ? "s" : "")
                  + " en échec"
                  + (f.failedChannels().isEmpty()
                      ? ""
                      : " (" + String.join(", ", f.failedChannels()) + ")")
                  + ".",
              "Tester le canal depuis l'écran des canaux de notification et corriger sa"
                  + " référence de secret."));
    }
    if (f.notificationsSent() == 0 && !f.underMaintenance() && f.alertCount() > 0) {
      findings.add(
          new Finding(
              WARNING,
              "Aucune notification n'a été envoyée pour cet incident.",
              "Vérifier que quelqu'un a une préférence de notification active pour cette"
                  + " sévérité, ou un planning d'astreinte qui couvre le site."));
    }

    // --- Escalade ------------------------------------------------------------------
    if (f.onCallUnreachable()) {
      findings.add(
          new Finding(
              ACTION,
              "La personne d'astreinte n'a pas pu être prévenue (aucun canal ou personne de garde).",
              "Compléter le planning d'astreinte et vérifier que chaque personne de garde a un"
                  + " canal de notification actif."));
    }
    if (f.secondaryOnCallCalled()) {
      findings.add(
          new Finding(
              WARNING,
              "L'astreinte secondaire a dû être prévenue : la principale n'a pas réagi à temps.",
              "En parler avec la personne d'astreinte principale : canal manqué, téléphone"
                  + " silencieux, ou délai trop court."));
    }
    if (f.escalatedToAdmins()) {
      findings.add(
          new Finding(
              WARNING,
              "L'incident a été escaladé aux administrateurs.",
              "Une escalade signale un trou dans l'organisation : planning, canal ou charge de"
                  + " l'équipe."));
    }

    // --- Récidive et cause -----------------------------------------------------
    if (f.rootEquipment() != null && f.previousIncidents() >= RECURRENCE) {
      findings.add(
          new Finding(
              ACTION,
              ordinal(f.previousIncidents() + 1)
                  + " incident sur "
                  + f.rootEquipment()
                  + " en "
                  + f.recurrenceWindowDays()
                  + " jours.",
              "Traiter la cause de fond plutôt que le symptôme : remplacement, redondance, ou"
                  + " action corrective automatique sur la règle concernée."));
    }
    if (f.resolved() && !f.causeConfirmed()) {
      findings.add(
          new Finding(
              WARNING,
              "Cause non confirmée.",
              "Confirmer la vraie cause dans le diagnostic de l'alerte : c'est ce qui affine le"
                  + " classement des causes des prochains incidents."));
    } else if (f.causeConfirmed()) {
      findings.add(new Finding(POSITIVE, "Cause confirmée par un opérateur.", null));
    }

    // --- Usagers ----------------------------------------------------------------
    if (f.publicComponents() > 0 && f.publicCommunications() == 0) {
      findings.add(
          new Finding(
              ACTION,
              f.publicComponents()
                  + " composant"
                  + (f.publicComponents() > 1 ? "s" : "")
                  + " de page de statut publique touché"
                  + (f.publicComponents() > 1 ? "s" : "")
                  + " sans communication aux usagers.",
              "Publier un incident sur la page de statut dès la prise en charge, ou activer"
                  + " l'incident public automatique."));
    }
    if (f.underMaintenance()) {
      findings.add(
          new Finding(
              POSITIVE,
              "Une maintenance planifiée couvrait l'incident : les notifications ont été"
                  + " suspendues comme prévu.",
              null));
    }
    return List.copyOf(findings);
  }

  static String minutes(double value) {
    if (value < 1) {
      return Math.max(1, Math.round(value * 60)) + " s";
    }
    if (value < 120) {
      return String.format(Locale.FRANCE, "%.0f min", value);
    }
    long total = Math.round(value);
    return (total / 60) + " h " + String.format(Locale.FRANCE, "%02d", total % 60);
  }

  private static String ordinal(int n) {
    return n == 1 ? "1er" : n + "e";
  }
}
