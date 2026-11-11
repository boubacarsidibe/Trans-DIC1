package sn.ept.transdic1.flow.receiver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gabarits NetFlow v9 annoncés par les exportateurs.
 *
 * <p>En v9, un paquet de données ne dit pas ce qu'il contient : il renvoie à un gabarit envoyé
 * séparément, en général toutes les quelques minutes. Il faut donc les retenir — et accepter que
 * les premiers paquets de données d'un exportateur soient illisibles, jusqu'à son premier gabarit.
 *
 * <p>Un gabarit est identifié par l'exportateur, son identifiant de source (un même équipement peut
 * exporter depuis plusieurs moteurs) et le numéro de gabarit.
 *
 * <p>Deux garde-fous, parce que ce cache est alimenté par le réseau : un nombre maximal de gabarits
 * retenus, et un oubli au bout de {@code flow.template-ttl-minutes}. Sans eux, un équipement en
 * panne — ou malveillant — qui annoncerait des milliers de gabarits ferait enfler la mémoire
 * indéfiniment.
 */
public class FlowTemplates {
  /** Au-delà, les nouveaux gabarits sont refusés plutôt que de faire enfler la mémoire. */
  static final int MAX_TEMPLATES = 2_000;

  /** Un champ d'un gabarit : son type NetFlow et sa longueur en octets. */
  public record Field(int type, int length) {}

  private record Key(String exporter, long sourceId, int templateId) {}

  private record Entry(List<Field> fields, Instant storedAt) {}

  private final Map<Key, Entry> templates = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration ttl;

  public FlowTemplates(Clock clock, Duration ttl) {
    this.clock = clock;
    this.ttl = ttl;
  }

  /** Retient un gabarit annoncé, ou l'ignore si le cache est plein. */
  public void remember(String exporter, long sourceId, int templateId, List<Field> fields) {
    if (fields.isEmpty()) {
      return;
    }
    var key = new Key(exporter, sourceId, templateId);
    if (templates.size() >= MAX_TEMPLATES && !templates.containsKey(key)) {
      return;
    }
    templates.put(key, new Entry(List.copyOf(fields), clock.instant()));
  }

  /** Gabarit connu pour ce paquet de données, ou liste vide s'il n'est pas (encore) connu. */
  public List<Field> find(String exporter, long sourceId, int templateId) {
    Entry entry = templates.get(new Key(exporter, sourceId, templateId));
    if (entry == null) {
      return List.of();
    }
    if (Duration.between(entry.storedAt(), clock.instant()).compareTo(ttl) > 0) {
      templates.remove(new Key(exporter, sourceId, templateId));
      return List.of();
    }
    return entry.fields();
  }

  /** Nombre de gabarits retenus, pour la supervision de la supervision. */
  public int size() {
    return templates.size();
  }

  /** Oublie tout : au redémarrage d'un exportateur, ses gabarits reviendront d'eux-mêmes. */
  public void clear() {
    templates.clear();
  }
}
