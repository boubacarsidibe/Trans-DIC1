package sn.ept.transdic1.chatops.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.chatops.service.ChatOpsService;

/**
 * Écoute du bot Telegram par interrogation longue ({@code getUpdates}) : la plateforme va chercher
 * les messages et les clics, Telegram n'a donc jamais besoin de joindre le serveur de l'EPT (aucune
 * adresse publique, aucun port entrant). Un seul fil, démarré avec l'application si la prise en
 * charge Telegram est activée et un jeton de bot configuré.
 *
 * <p>Une mise à jour est confirmée à Telegram en passant le décalage suivant à l'appel d'après :
 * une mise à jour dont le traitement échoue n'est pas rejouée en boucle, elle est journalisée et
 * dépassée. Une panne du réseau ralentit la boucle (attente croissante, jusqu'à une minute) sans
 * jamais l'arrêter.
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramPoller implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(TelegramPoller.class);
  private static final int LONG_POLL_SECONDS = 25;

  private final TelegramBot bot;
  private final ChatOpsService chatOps;
  private final boolean enabled;
  private volatile boolean running;
  private Thread thread;

  public TelegramPoller(
      TelegramBot bot,
      ChatOpsService chatOps,
      @Value("${chatops.telegram.enabled:false}") boolean enabled) {
    this.bot = bot;
    this.chatOps = chatOps;
    this.enabled = enabled;
  }

  @Override
  public void start() {
    if (!enabled) {
      return;
    }
    if (!bot.configured()) {
      LOG.warn("Prise en charge Telegram activée mais aucun jeton de bot : écoute non démarrée");
      return;
    }
    running = true;
    thread = new Thread(this::loop, "telegram-poller");
    thread.setDaemon(true);
    thread.start();
    LOG.info("Écoute du bot Telegram démarrée");
  }

  private void loop() {
    long offset = 0;
    long backoffMs = 1000;
    while (running) {
      try {
        List<JsonNode> updates = bot.updates(offset, LONG_POLL_SECONDS);
        backoffMs = 1000;
        for (JsonNode update : updates) {
          offset = Math.max(offset, update.path("update_id").asLong() + 1);
          try {
            chatOps.handle(update);
          } catch (RuntimeException e) {
            LOG.warn(
                "Mise à jour Telegram {} non traitée : {}",
                update.path("update_id").asLong(),
                e.getMessage());
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        LOG.warn(
            "Bot Telegram injoignable ({}) : nouvel essai dans {} s",
            e.getMessage(),
            backoffMs / 1000);
        sleep(backoffMs);
        backoffMs = Math.min(backoffMs * 2, 60_000);
      }
    }
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      running = false;
    }
  }

  @Override
  public void stop() {
    running = false;
    if (thread != null) {
      thread.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
