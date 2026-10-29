package sn.ept.transdic1.collection.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.port.MetricSource;
import sn.ept.transdic1.inventory.domain.Equipment;

/**
 * Source simulée : CPU, mémoire, disque et disponibilité réalistes et reproductibles. Chaque valeur
 * dépend uniquement de l'équipement et de l'instant (aucun état, aucun hasard non déterministe) :
 * deux lectures du même instant donnent les mêmes mesures, ce qui rend les tests rapides et les
 * démonstrations rejouables. Elle ne tourne que si la collecte simulée est activée ({@code
 * collection.simulated.enabled}).
 */
@Component
public class SimulatedMetricSource implements MetricSource {
  static final String CPU = "cpu.usage";
  static final String MEMORY = "memory.usage";
  static final String DISK = "disk.usage";
  static final String AVAILABILITY = "availability";

  private static final long MINUTE = 60;

  @Override
  public String name() {
    return "simulated";
  }

  @Override
  public List<MeasureInput> read(Equipment equipment, Instant at) {
    // Horodatage UTC à la seconde : stable et sans fraction parasite.
    Instant collectedAt = at.truncatedTo(ChronoUnit.SECONDS);
    UUID id = equipment.getId();
    long second = collectedAt.getEpochSecond();
    return List.of(
        measure(CPU, cpu(id, second), collectedAt),
        measure(MEMORY, memory(id, second), collectedAt),
        measure(DISK, disk(id, second), collectedAt),
        measure(AVAILABILITY, availability(id, second), collectedAt));
  }

  // Charge CPU : cycle d'une heure autour de 35 %, avec un bruit court.
  static double cpu(UUID id, long second) {
    return clamp(35 + 25 * wave(id, "cpu", second, 3600) + 6 * noise(id, "cpu", second / MINUTE));
  }

  // Mémoire : cycle lent de quatre heures autour de 60 %.
  static double memory(UUID id, long second) {
    return clamp(
        60 + 15 * wave(id, "mem", second, 4 * 3600) + 2 * noise(id, "mem", second / MINUTE));
  }

  // Disque : remplissage lent et régulier, entre 40 et 90 %, sur un cycle de 200 jours.
  static double disk(UUID id, long second) {
    double fraction = (second % (200L * 86400)) / (200.0 * 86400);
    return clamp(40 + 50 * fraction + 0.5 * noise(id, "disk", second / (6 * 3600)));
  }

  // Disponibilité : 100 %, avec de rares minutes d'indisponibilité (environ une sur 600).
  static double availability(UUID id, long second) {
    return Math.floorMod(hash(id, "up", second / MINUTE), 600) == 0 ? 0 : 100;
  }

  /** Sinusoïde de période donnée, déphasée par équipement : entre -1 et 1. */
  private static double wave(UUID id, String salt, long second, long periodSeconds) {
    double phase = Math.floorMod(hash(id, salt, 0), 360) * Math.PI / 180;
    return Math.sin(2 * Math.PI * second / periodSeconds + phase);
  }

  /** Bruit déterministe entre -1 et 1 pour une tranche de temps donnée. */
  private static double noise(UUID id, String salt, long slot) {
    return Math.floorMod(hash(id, salt, slot), 2001) / 1000.0 - 1;
  }

  private static long hash(UUID id, String salt, long slot) {
    long h = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17);
    h ^= salt.hashCode() * 0x9E3779B97F4A7C15L;
    h ^= slot * 0xBF58476D1CE4E5B9L;
    h ^= h >>> 31;
    h *= 0x94D049BB133111EBL;
    return h ^ (h >>> 29);
  }

  private static double clamp(double value) {
    return Math.max(0, Math.min(100, value));
  }

  private static MeasureInput measure(String key, double value, Instant at) {
    return new MeasureInput(
        key, BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP), "%", "GOOD", at);
  }
}
