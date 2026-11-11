package sn.ept.transdic1.flow.service;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Qui a le droit d'exporter des flux : l'adresse d'où arrive un paquet NetFlow doit être l'adresse
 * de gestion d'un équipement supervisé, sinon le paquet est jeté sans rien conserver.
 *
 * <p>Même principe que pour le récepteur syslog (ADR 0021) et pour la même raison : l'UDP se
 * falsifie, et n'importe qui sur le réseau pourrait sinon faire grossir la base de la supervision
 * avec du trafic inventé.
 *
 * <p>L'inventaire est relu d'une seule requête toutes les {@code flow.source-cache-seconds}
 * secondes : le thread du socket ne doit jamais attendre la base.
 */
@Component
public class FlowSources {
  /**
   * Ce qui peut être une adresse IP littérale : chiffres, lettres hexadécimales, points,
   * deux-points.
   */
  private static final java.util.regex.Pattern LITERAL =
      java.util.regex.Pattern.compile("[0-9a-fA-F.:]{2,45}");

  private record Snapshot(Map<String, UUID> byAddress, Instant loadedAt) {}

  private final EquipmentRepository equipments;
  private final Clock clock;
  private final Duration ttl;
  private volatile Snapshot snapshot;

  public FlowSources(
      EquipmentRepository equipments,
      Clock clock,
      @Value("${flow.source-cache-seconds:30}") long cacheSeconds) {
    this.equipments = equipments;
    this.clock = clock;
    this.ttl = Duration.ofSeconds(Math.max(0, cacheSeconds));
  }

  /** Équipement supervisé qui porte cette adresse, ou null si aucune. */
  public UUID equipmentAt(String address) {
    if (address == null) {
      return null;
    }
    return current().byAddress().get(canonical(address));
  }

  /** Oublie le cache : le prochain appel relit l'inventaire. */
  public void invalidate() {
    snapshot = null;
  }

  private Snapshot current() {
    Snapshot current = snapshot;
    Instant now = clock.instant();
    if (current == null
        || ttl.isZero()
        || Duration.between(current.loadedAt(), now).compareTo(ttl) >= 0) {
      current = load(now);
      if (!ttl.isZero()) {
        snapshot = current;
      }
    }
    return current;
  }

  private Snapshot load(Instant now) {
    var fresh = new HashMap<String, UUID>();
    for (Object[] row : equipments.findMonitoredSources()) {
      if (row[0] != null) {
        // Deux équipements à la même adresse : le premier gagne, comme ailleurs. L'inventaire
        // n'est pas censé en contenir.
        fresh.putIfAbsent(canonical((String) row[0]), (UUID) row[1]);
      }
    }
    return new Snapshot(Map.copyOf(fresh), now);
  }

  /**
   * Forme canonique d'une adresse, pour que {@code 10.1.2.3} et {@code ::ffff:10.1.2.3} ne soient
   * pas deux sources différentes.
   *
   * <p>Seules les adresses littérales sont converties : un texte qui n'en est pas une est rendu tel
   * quel, en minuscules. Sans cette précaution, {@code InetAddress} irait interroger le DNS depuis
   * le thread du socket, et il suffirait de lui envoyer des noms inventés pour le bloquer.
   */
  static String canonical(String address) {
    String text = address.trim();
    if (!LITERAL.matcher(text).matches()) {
      return text.toLowerCase(Locale.ROOT);
    }
    try {
      return InetAddress.getByName(text).getHostAddress();
    } catch (Exception e) {
      return text.toLowerCase(Locale.ROOT);
    }
  }
}
