package sn.ept.transdic1.chatops.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.common.secret.SecretResolver;

/**
 * Implémentation HTTP de l'API des bots Telegram. Le jeton du bot est une référence de secret
 * ({@code env:TELEGRAM_BOT_TOKEN}) résolue à chaque appel : il ne passe jamais par la base, ni par
 * un journal, ni par un message d'erreur (une erreur ne cite que le code HTTP et la méthode).
 */
@Component
public class HttpTelegramBot implements TelegramBot {
  private final SecretResolver secrets;
  private final ObjectMapper json;
  private final String tokenReference;
  private final String apiUrl;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public HttpTelegramBot(
      SecretResolver secrets,
      ObjectMapper json,
      @Value("${chatops.telegram.bot-token:}") String tokenReference,
      @Value("${chatops.telegram.api-url:https://api.telegram.org}") String apiUrl) {
    this.secrets = secrets;
    this.json = json;
    this.tokenReference = tokenReference;
    this.apiUrl = apiUrl.replaceAll("/+$", "");
  }

  @Override
  public boolean configured() {
    if (tokenReference == null || tokenReference.isBlank()) {
      return false;
    }
    try {
      String token = secrets.resolve(tokenReference);
      return token != null && !token.isBlank();
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public List<JsonNode> updates(long offset, int timeoutSeconds) throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("offset", offset);
    body.put("timeout", timeoutSeconds);
    body.putArray("allowed_updates").add("message").add("callback_query");
    JsonNode response = call("getUpdates", body, Duration.ofSeconds(timeoutSeconds + 10L));
    List<JsonNode> updates = new ArrayList<>();
    response.path("result").forEach(updates::add);
    return updates;
  }

  @Override
  public void sendMessage(long chatId, String text, String replyMarkupJson) throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("chat_id", chatId);
    body.put("text", text);
    body.put("disable_web_page_preview", true);
    if (replyMarkupJson != null) {
      body.set("reply_markup", json.readTree(replyMarkupJson));
    }
    call("sendMessage", body, Duration.ofSeconds(15));
  }

  @Override
  public void editMessage(long chatId, long messageId, String text, String replyMarkupJson)
      throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("chat_id", chatId);
    body.put("message_id", messageId);
    body.put("text", text);
    if (replyMarkupJson != null) {
      body.set("reply_markup", json.readTree(replyMarkupJson));
    }
    call("editMessageText", body, Duration.ofSeconds(15));
  }

  @Override
  public void answerCallback(String callbackId, String text) throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("callback_query_id", callbackId);
    body.put("text", text);
    call("answerCallbackQuery", body, Duration.ofSeconds(15));
  }

  private JsonNode call(String method, JsonNode body, Duration timeout) throws Exception {
    URI uri = URI.create(apiUrl + "/bot" + secrets.resolve(tokenReference) + "/" + method);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("Telegram " + method + " : HTTP " + response.statusCode());
    }
    return json.readTree(response.body());
  }
}
