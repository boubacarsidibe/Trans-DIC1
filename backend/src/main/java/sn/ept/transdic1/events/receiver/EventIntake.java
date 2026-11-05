package sn.ept.transdic1.events.receiver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sn.ept.transdic1.events.service.EventMetrics;

/**
 * Entre les sockets et la base : limitation de débit par source, file bornée et pool de
 * travailleurs. {@link #submit} ne bloque jamais le thread du socket : un événement en trop est
 * rejeté et compté. Les travailleurs sont démarrés par le premier récepteur et arrêtés à la
 * fermeture du contexte.
 */
public class EventIntake implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(EventIntake.class);

  private final BlockingQueue<ReceivedEvent> queue;
  private final int workerCount;
  private final SourceRateLimiter limiter;
  private final Consumer<ReceivedEvent> handler;
  private final EventMetrics metrics;
  private final List<Thread> workers = new ArrayList<>();
  private volatile boolean running;

  public EventIntake(
      int capacity,
      int workerCount,
      SourceRateLimiter limiter,
      Consumer<ReceivedEvent> handler,
      EventMetrics metrics) {
    this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    this.workerCount = Math.max(1, workerCount);
    this.limiter = limiter;
    this.handler = handler;
    this.metrics = metrics;
  }

  /** Démarre les travailleurs ; sans effet s'ils tournent déjà. */
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    for (int i = 0; i < workerCount; i++) {
      Thread worker = new Thread(this::work, "evenements-" + (i + 1));
      worker.setDaemon(true);
      workers.add(worker);
      worker.start();
    }
  }

  public boolean isRunning() {
    return running;
  }

  /** Accepte l'événement s'il reste de la place ; ne bloque jamais. */
  public boolean submit(ReceivedEvent event) {
    if (!running) {
      metrics.dropped("stopped");
      return false;
    }
    if (!limiter.tryAcquire(event.sourceAddress())) {
      metrics.dropped("rate_limited");
      return false;
    }
    if (!queue.offer(event)) {
      metrics.dropped("queue_full");
      return false;
    }
    return true;
  }

  public int queued() {
    return queue.size();
  }

  private void work() {
    while (running) {
      ReceivedEvent event;
      try {
        event = queue.poll(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (event == null) {
        continue;
      }
      try {
        handler.accept(event);
      } catch (RuntimeException e) {
        // Un événement en échec n'arrête pas le travailleur ; le message ne cite que la classe.
        LOG.warn(
            "Événement de {} non traité : {}", event.sourceAddress(), e.getClass().getSimpleName());
      }
    }
  }

  /** Arrête les travailleurs et attend leur fin (2 s au plus chacun). */
  @Override
  public synchronized void close() {
    if (!running) {
      return;
    }
    running = false;
    for (Thread worker : workers) {
      worker.interrupt();
    }
    for (Thread worker : workers) {
      try {
        worker.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    workers.clear();
    queue.clear();
  }
}
