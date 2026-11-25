package sn.ept.transdic1.chatops.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Ce dont la plateforme a besoin de l'API des bots Telegram : recevoir les messages et les clics,
 * répondre, et modifier un message déjà envoyé. Port séparé de son implémentation HTTP pour que la
 * logique se vérifie sans réseau.
 */
public interface TelegramBot {
  /** Vrai si un jeton de bot est configuré. */
  boolean configured();

  /** Attend au plus {@code timeoutSeconds} de nouvelles mises à jour à partir de {@code offset}. */
  List<JsonNode> updates(long offset, int timeoutSeconds) throws Exception;

  void sendMessage(long chatId, String text, String replyMarkupJson) throws Exception;

  /** Remplace le texte d'un message ; {@code replyMarkupJson} nul retire ses boutons. */
  void editMessage(long chatId, long messageId, String text, String replyMarkupJson)
      throws Exception;

  void answerCallback(String callbackId, String text) throws Exception;
}
