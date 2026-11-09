package sn.ept.transdic1.events.receiver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sn.ept.transdic1.events.service.EventMetrics;

/**
 * Entre les sockets et la base (ADR 0021) :
 *
 * <ul>
 *   <li>une source inconnue (adresse absente de l'inventaire supervisé, lue dans un cache) est
 *       rejetée tout de suite, avant le limiteur et la file : des sources usurpées en masse ne
 *       prennent ni place dans la file ni transaction ;
 *   <li>limitation de débit par source connue ;
 *   <li>une file bornée par travailleur, choisie par le hachage de l'adresse source : les
 *       événements d'une même source sont traités dans l'ordre, par un seul travailleur (pas de
 *       course entre deux travailleurs sur la même alerte, linkDown puis linkUp jamais inversés).
 * </ul>
 *
 * {@link #submit} ne bloque jamais le thread du socket : un événement en trop est rejeté et compté.
 * Les travailleurs sont démarrés par le premier récepteur et arrêtés à la fermeture du contexte.
 */
public class EventIntake implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(EventIntake.class);

  private final List<BlockingQueue<ReceivedEvent>> queues = new ArrayList<>();
  private final int workerCount;
  private final Predicate<String> knownSource;
  private final SourceRateLimiter limiter;
  private final Consumer<ReceivedEvent> handler;
  private final EventMetrics metrics;
  private final List<Thread> workers = new ArrayList<>();
  private volatile boolean running;

  /**
   * @param capacity capacité totale, répartie entre les files des travailleurs (une place au moins
   *     par file)
   * @param knownSource vrai si l'adresse est celle d'un équipement supervisé (sans base)
   */
  public EventIntake(
      int capacity,
      int workerCount,
      Predicate<String> knownSource,
      SourceRateLimiter limiter,
      Consumer<ReceivedEvent> handler,
      EventMetrics metrics) {
    this.workerCount = Math.max(1, workerCount);
    int perQueue = Math.max(1, Math.max(1, capacity) / this.workerCount);
    for (int i = 0; i < this.workerCount; i++) {
      queues.add(new ArrayBlockingQueue<>(perQueue));
    }
    this.knownSource = knownSource;
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
      BlockingQueue<ReceivedEvent> queue = queues.get(i);
      Thread worker = new Thread(() -> work(queue), "evenements-" + (i + 1));
      worker.setDaemon(true);
      workers.add(worker);
      worker.start();
    }
  }

  public boolean isRunning() {
    return running;
  }

  /** Accepte l'événement s'il vient d'une source connue et qu'il reste de la place. */
  public boolean submit(ReceivedEvent event) {
    if (!running) {
      metrics.dropped("stopped");
      return false;
    }
    String source = event.sourceAddress();
    if (source == null || !knownSource.test(source)) {
      metrics.dropped("unknown_source");
      return false;
    }
    if (!limiter.tryAcquire(source)) {
      metrics.dropped("rate_limited");
      return false;
    }
    if (!queues.get(Math.floorMod(source.hashCode(), workerCount)).offer(event)) {
      metrics.dropped("queue_full");
      return false;
    }
    return true;
  }

  public int queued() {
    int total = 0;
    for (BlockingQueue<ReceivedEvent> queue : queues) {
      total += queue.size();
    }
    return total;
  }

  private void work(BlockingQueue<ReceivedEvent> queue) {
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
    queues.forEach(BlockingQueue::clear);
  }
}
