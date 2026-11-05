package sn.ept.transdic1.events.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Annuaire des sources d'événements (ADR 0021) : adresse de gestion canonique (voir {@link
 * IpLiterals}) vers les équipements supervisés qui la portent, avec leur site. Rechargé d'une seule
 * requête toutes les {@code events.source-cache-seconds} secondes (0 : à chaque appel, pour les
 * tests). Sert au rejet des sources inconnues avant la file et au rattachement sans requête par
 * événement.
 */
@Component
public class EventSourceDirectory {

  /** Un équipement supervisé à cette adresse et son site (peut être nul). */
  public record SourceEquipment(UUID equipmentId, UUID siteId) {}

  private record Snapshot(Map<String, List<SourceEquipment>> byAddress, Instant loadedAt) {}

  private final EquipmentRepository equipments;
  private final Clock clock;
  private final Duration ttl;
  private volatile Snapshot snapshot;

  public EventSourceDirectory(
      EquipmentRepository equipments,
      Clock clock,
      @Value("${events.source-cache-seconds:30}") long cacheSeconds) {
    this.equipments = equipments;
    this.clock = clock;
    this.ttl = Duration.ofSeconds(Math.max(0, cacheSeconds));
  }

  /** Vrai si un équipement supervisé porte cette adresse. */
  public boolean isKnown(String address) {
    return !lookup(address).isEmpty();
  }

  /** Équipements supervisés à cette adresse (vide si inconnue). */
  public List<SourceEquipment> lookup(String address) {
    if (address == null) {
      return List.of();
    }
    List<SourceEquipment> found = current().byAddress().get(IpLiterals.canonical(address));
    return found == null ? List.of() : found;
  }

  /** Oublie le cache : le prochain appel relit l'inventaire. */
  public void invalidate() {
    snapshot = null;
  }

  private Snapshot current() {
    Snapshot s = snapshot;
    Instant now = clock.instant();
    if (s == null || ttl.isZero() || Duration.between(s.loadedAt(), now).compareTo(ttl) >= 0) {
      s = load(now);
      if (!ttl.isZero()) {
        snapshot = s;
      }
    }
    return s;
  }

  private Snapshot load(Instant now) {
    Map<String, List<SourceEquipment>> fresh = new HashMap<>();
    for (Object[] row : equipments.findMonitoredSources()) {
      if (row[0] == null) {
        continue;
      }
      fresh
          .computeIfAbsent(IpLiterals.canonical((String) row[0]), k -> new ArrayList<>())
          .add(new SourceEquipment((UUID) row[1], (UUID) row[2]));
    }
    Map<String, List<SourceEquipment>> frozen = new HashMap<>();
    fresh.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
    return new Snapshot(Map.copyOf(frozen), now);
  }
}
