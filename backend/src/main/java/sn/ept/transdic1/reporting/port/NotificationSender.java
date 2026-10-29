package sn.ept.transdic1.reporting.port;

import sn.ept.transdic1.reporting.domain.NotificationChannel;

/**
 * Port d'envoi d'un message : un adaptateur par canal (e-mail, webhook, Teams, Slack). Un échec se
 * signale par une exception ; l'appelant la consigne sans jamais en afficher les détails bruts.
 */
public interface NotificationSender {
  NotificationChannel channel();

  /**
   * @param destination adresse e-mail, ou référence de secret (env:NOM) qui contient l'URL du canal
   */
  void send(String destination, String subject, String body) throws Exception;
}
