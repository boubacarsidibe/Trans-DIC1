package sn.ept.transdic1.overview.service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;

/**
 * Pousse aux écrans ouverts un signal « les alertes ont changé » (SSE, ADR-0006). Le signal part
 * seulement une fois la transaction validée : l'écran qui recharge ses données y trouve déjà le
 * changement, et une transaction annulée ne déclenche rien. Le signal ne transporte aucune donnée
 * d'alerte : les écrans rechargent par l'API, qui applique les droits de l'utilisateur.
 */
@Service
public class LiveUpdateService {
  private static final Logger LOG = LoggerFactory.getLogger(LiveUpdateService.class);

  private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
  private final int maxClients;
  private final long timeoutMillis;
  private final boolean brokered;

  public LiveUpdateService(
      @Value("${live.max-clients:200}") int maxClients,
      @Value("${live.timeout-seconds:1800}") long timeoutSeconds,
      @Value("${broker.enabled:false}") boolean brokered) {
    this.brokered = brokered;
    this.maxClients = maxClients;
    this.timeoutMillis = timeoutSeconds * 1000;
  }

  /** Ouvre un flux ; retourne null si trop de clients sont déjà connectés. */
  public SseEmitter register() {
    if (clients.size() >= maxClients) {
      return null;
    }
    var emitter = new SseEmitter(timeoutMillis);
    clients.add(emitter);
    emitter.onCompletion(() -> clients.remove(emitter));
    emitter.onTimeout(() -> clients.remove(emitter));
    emitter.onError(error -> clients.remove(emitter));
    try {
      emitter.send(SseEmitter.event().name("ready").data("{}"));
    } catch (IOException | IllegalStateException e) {
      clients.remove(emitter);
    }
    return emitter;
  }

  /** Après validation de la transaction ; sans transaction en cours, tout de suite. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onAlertActivity(AlertActivityEvent event) {
    // Avec le broker (plusieurs instances), le signal arrive par EventSubscriber sur chaque
    // instance : on ne le diffuse pas une seconde fois ici.
    if (!brokered) {
      broadcast("alerts", "{\"type\":\"" + event.type().name() + "\"}");
    }
  }

  /** Signal reçu du broker (contrat v1) : diffusé tel quel aux écrans de cette instance. */
  public void relay(String payload) {
    broadcast("alerts", payload);
  }

  /**
   * Un battement régulier évite qu'un proxy coupe un flux silencieux et révèle les clients partis.
   */
  @Scheduled(fixedDelayString = "${live.heartbeat-ms:25000}")
  public void heartbeat() {
    for (SseEmitter client : clients) {
      try {
        client.send(SseEmitter.event().comment("ping"));
      } catch (IOException | IllegalStateException e) {
        clients.remove(client);
      }
    }
  }

  public int clientCount() {
    return clients.size();
  }

  private void broadcast(String name, String json) {
    for (SseEmitter client : clients) {
      try {
        client.send(SseEmitter.event().name(name).data(json));
      } catch (IOException | IllegalStateException e) {
        clients.remove(client);
        LOG.debug("Client de flux retiré : {}", e.toString());
      }
    }
  }
}
