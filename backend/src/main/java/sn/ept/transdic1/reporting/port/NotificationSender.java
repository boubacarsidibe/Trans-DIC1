package sn.ept.transdic1.reporting.port;

import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.reporting.domain.NotificationChannel;

/**
 * Port d'envoi d'un message : un adaptateur par canal (e-mail, webhook, Teams, Slack, Telegram,
 * syslog). Un échec se signale par une exception ; l'appelant la consigne sans jamais en afficher
 * les détails bruts.
 */
public interface NotificationSender {
  NotificationChannel channel();

  /**
   * Ce que le canal peut savoir du message au-delà du texte. La plupart des canaux n'en ont que
   * faire : un courriel ou un message Teams se lit. Un collecteur syslog, lui, classe et filtre sur
   * la gravité, d'où ce contexte facultatif.
   *
   * @param severity sévérité de l'alerte à l'origine du message, ou nulle (rapport, essai de canal)
   * @param messageId court identifiant du type de message, pour le tri côté collecteur
   */
  record Context(Severity severity, String messageId) {
    /** Contexte d'un message sans alerte : essai de canal, rapport planifié. */
    public static Context unknown() {
      return new Context(null, "MESSAGE");
    }
  }

  /**
   * @param destination adresse e-mail, ou référence de secret (env:NOM) qui contient l'URL du canal
   */
  void send(String destination, String subject, String body) throws Exception;

  /**
   * Envoi avec le contexte du message. Par défaut, le contexte est ignoré : seuls les canaux qui en
   * tirent quelque chose redéfinissent cette méthode.
   */
  default void send(String destination, String subject, String body, Context context)
      throws Exception {
    send(destination, subject, body);
  }
}
