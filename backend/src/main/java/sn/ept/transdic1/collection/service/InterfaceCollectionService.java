package sn.ept.transdic1.collection.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.SnmpClient.Reading;
import sn.ept.transdic1.collection.service.SnmpClient.SnmpFailure;
import sn.ept.transdic1.collection.service.SnmpClient.Target;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.domain.CollectionConfiguration;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentInterface;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.EquipmentInterfaceRepository;

/**
 * Découverte et suivi des interfaces réseau par SNMP (IF-MIB). Deux temps séparés, parce qu'ils
 * n'ont pas le même coût :
 *
 * <ul>
 *   <li><b>Découverte</b> : parcours des colonnes de la table (nom, description, type, vitesse,
 *       état). Cher en requêtes, donc rare (une fois par heure par défaut, et au premier passage).
 *   <li><b>Relevé</b> : lecture directe des compteurs des interfaces déjà connues, par paquets
 *       d'OID. À chaque passage.
 * </ul>
 *
 * <p>Les compteurs d'IF-MIB sont cumulés : le débit vient de l'écart avec le passage précédent
 * ({@link InterfaceRate}). Un premier passage ne produit donc aucune mesure de débit.
 *
 * <p>Les mesures portent le nom de l'interface en instance ({@code net.in[Gi0/1]}), comme l'agent
 * pour les disques : les règles par famille ({@code net.usage[*]}) s'y appliquent directement.
 */
@Service
public class InterfaceCollectionService {
  private static final Logger LOG = LoggerFactory.getLogger(InterfaceCollectionService.class);

  static final String IF_TYPE = "1.3.6.1.2.1.2.2.1.3";
  static final String IF_DESCR = "1.3.6.1.2.1.2.2.1.2";
  static final String IF_ADMIN_STATUS = "1.3.6.1.2.1.2.2.1.7";
  static final String IF_OPER_STATUS = "1.3.6.1.2.1.2.2.1.8";
  static final String IF_IN_DISCARDS = "1.3.6.1.2.1.2.2.1.13";
  static final String IF_IN_ERRORS = "1.3.6.1.2.1.2.2.1.14";
  static final String IF_OUT_DISCARDS = "1.3.6.1.2.1.2.2.1.19";
  static final String IF_OUT_ERRORS = "1.3.6.1.2.1.2.2.1.20";
  static final String IF_NAME = "1.3.6.1.2.1.31.1.1.1.1";
  static final String IF_HC_IN_OCTETS = "1.3.6.1.2.1.31.1.1.1.6";
  static final String IF_HC_OUT_OCTETS = "1.3.6.1.2.1.31.1.1.1.10";
  static final String IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15";
  static final String IF_ALIAS = "1.3.6.1.2.1.31.1.1.1.18";

  /** Interface de bouclage : toujours à 100 %, jamais en panne, sans intérêt à superviser. */
  static final int TYPE_SOFTWARE_LOOPBACK = 24;

  /** Compteurs lus par requête : au-delà, la réponse UDP risque d'être tronquée. */
  static final int OIDS_PER_REQUEST = 30;

  /** Colonnes lues à chaque relevé, dans cet ordre. */
  private static final List<String> COUNTER_COLUMNS =
      List.of(
          IF_HC_IN_OCTETS,
          IF_HC_OUT_OCTETS,
          IF_IN_ERRORS,
          IF_OUT_ERRORS,
          IF_IN_DISCARDS,
          IF_OUT_DISCARDS,
          IF_OPER_STATUS);

  private final CollectionConfigurationRepository configurations;
  private final EquipmentInterfaceRepository interfaces;
  private final MeasureIngestionService ingestion;
  private final SnmpClient client;
  private final SnmpTargets targets;
  private final TransactionTemplate transaction;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private final Duration discoveryInterval;
  private final int maxInterfaces;

  public InterfaceCollectionService(
      CollectionConfigurationRepository configurations,
      EquipmentInterfaceRepository interfaces,
      MeasureIngestionService ingestion,
      SnmpClient client,
      SnmpTargets targets,
      TransactionTemplate transaction,
      JobGuard guard,
      Clock clock,
      @Value("${collection.snmp.interfaces.enabled:true}") boolean enabled,
      @Value("${collection.snmp.interfaces.discovery-interval-minutes:60}") long discoveryMinutes,
      @Value("${collection.snmp.interfaces.max-per-equipment:256}") int maxInterfaces) {
    this.configurations = configurations;
    this.interfaces = interfaces;
    this.ingestion = ingestion;
    this.client = client;
    this.targets = targets;
    this.transaction = transaction;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    this.discoveryInterval = Duration.ofMinutes(Math.max(5, discoveryMinutes));
    this.maxInterfaces = Math.max(1, maxInterfaces);
  }

  @Scheduled(fixedDelayString = "${collection.snmp.interfaces.scan-interval-ms:60000}")
  public void scheduledScan() {
    if (enabled) {
      guard.run("collection.snmp-interfaces", Duration.ofMinutes(5), this::collectAll);
    }
  }

  /** Un passage sur toutes les configurations concernées ; retourne le nombre d'équipements lus. */
  public int collectAll() {
    List<UUID> due =
        transaction.execute(
            status ->
                configurations.findAll().stream()
                    .filter(CollectionConfiguration::isActive)
                    .filter(c -> c.getMode() == CollectionMode.SNMP)
                    .filter(CollectionConfiguration::isDiscoverInterfaces)
                    .filter(c -> c.getEquipment().isMonitoringEnabled())
                    .map(CollectionConfiguration::getId)
                    .toList());
    if (due == null || due.isEmpty()) {
      return 0;
    }
    int done = 0;
    for (UUID id : due) {
      if (collectSafely(id)) {
        done++;
      }
    }
    return done;
  }

  private boolean collectSafely(UUID configurationId) {
    try {
      return Boolean.TRUE.equals(transaction.execute(status -> collectOne(configurationId)));
    } catch (RuntimeException e) {
      // Un équipement défaillant ne doit jamais arrêter les autres.
      LOG.error("Interfaces SNMP en erreur pour la configuration {} : {}", configurationId, e);
      return false;
    }
  }

  private boolean collectOne(UUID configurationId) {
    CollectionConfiguration configuration = configurations.findById(configurationId).orElse(null);
    if (configuration == null) {
      return false;
    }
    Equipment equipment = configuration.getEquipment();
    Target target = targets.of(configuration, equipment.getManagementAddress());
    Instant now = clock.instant();
    try {
      List<EquipmentInterface> known =
          interfaces.findByEquipmentIdOrderByIfIndex(equipment.getId());
      if (known.isEmpty() || isDiscoveryDue(known, now)) {
        known = discover(target, equipment, known, now);
      }
      measure(target, known, now);
      return true;
    } catch (SnmpFailure e) {
      // L'alerte de collecte reste le travail de la lecture des indicateurs : ici, on trace.
      LOG.warn(
          "Interfaces de {} illisibles en SNMP : {}",
          equipment.getManagementAddress(),
          e.getMessage());
      return false;
    }
  }

  /**
   * La découverte est due quand la plus ancienne interface connue date de plus d'un intervalle.
   * {@code lastSeenAt} n'est posé que par la découverte, jamais par un relevé : sans cela, un
   * équipement dont tous les ports sont suivis ne vieillirait jamais et un port ajouté
   * n'apparaîtrait pas.
   */
  private boolean isDiscoveryDue(List<EquipmentInterface> known, Instant now) {
    return known.stream()
        .map(EquipmentInterface::getLastSeenAt)
        .min(Instant::compareTo)
        .map(oldest -> oldest.isBefore(now.minus(discoveryInterval)))
        .orElse(true);
  }

  /** Parcourt la table des interfaces et met l'inventaire à jour (création, nom, vitesse, état). */
  List<EquipmentInterface> discover(
      Target target, Equipment equipment, List<EquipmentInterface> known, Instant now)
      throws SnmpFailure {
    Map<Integer, String> names = text(client.walk(target, IF_NAME, maxInterfaces));
    if (names.isEmpty()) {
      // Certains équipements ne servent que la table historique : ifDescr fait l'affaire.
      names = text(client.walk(target, IF_DESCR, maxInterfaces));
    }
    if (names.isEmpty()) {
      throw new SnmpFailure("Aucune interface dans la table IF-MIB");
    }
    Map<Integer, String> aliases = text(client.walk(target, IF_ALIAS, maxInterfaces));
    Map<Integer, BigDecimal> types = numbers(client.walk(target, IF_TYPE, maxInterfaces));
    Map<Integer, BigDecimal> speeds = numbers(client.walk(target, IF_HIGH_SPEED, maxInterfaces));
    Map<Integer, BigDecimal> admin = numbers(client.walk(target, IF_ADMIN_STATUS, maxInterfaces));

    Map<Integer, EquipmentInterface> byIndex = new LinkedHashMap<>();
    known.forEach(item -> byIndex.put(item.getIfIndex(), item));
    var result = new ArrayList<EquipmentInterface>();
    for (var entry : names.entrySet()) {
      int index = entry.getKey();
      EquipmentInterface item = byIndex.get(index);
      if (item == null) {
        item = new EquipmentInterface(equipment, index, shortened(entry.getValue(), 120), now);
        LOG.info("Interface découverte sur {} : {}", equipment.getName(), entry.getValue());
      } else {
        item.setName(shortened(entry.getValue(), 120));
      }
      item.setAlias(shortened(aliases.get(index), 255));
      Integer type = types.containsKey(index) ? types.get(index).intValue() : null;
      item.setIfType(type);
      // ifHighSpeed est en mégabits par seconde ; on garde des bits par seconde.
      BigDecimal speed = speeds.get(index);
      item.setSpeedBps(speed == null ? null : speed.longValue() * 1_000_000L);
      item.setAdminUp(admin.containsKey(index) ? admin.get(index).intValue() == 1 : null);
      // Le bouclage n'apprend rien ; une interface désactivée par l'administrateur non plus.
      item.setMonitored(
          (type == null || type != TYPE_SOFTWARE_LOOPBACK)
              && !Boolean.FALSE.equals(item.getAdminUp()));
      item.setLastSeenAt(now);
      result.add(interfaces.save(item));
    }
    return result;
  }

  /** Lit les compteurs des interfaces suivies et dépose les mesures dérivées. */
  void measure(Target target, List<EquipmentInterface> known, Instant now) throws SnmpFailure {
    List<EquipmentInterface> watched =
        known.stream().filter(EquipmentInterface::isMonitored).toList();
    if (watched.isEmpty()) {
      return;
    }
    Map<String, BigDecimal> values = readCounters(target, watched);
    var measures = new ArrayList<MeasureInput>();
    for (EquipmentInterface item : watched) {
      measures.addAll(measuresFor(item, values, now));
      item.rememberCounters(
          counter(values, IF_HC_IN_OCTETS, item),
          counter(values, IF_HC_OUT_OCTETS, item),
          counter(values, IF_IN_ERRORS, item),
          counter(values, IF_OUT_ERRORS, item),
          counter(values, IF_IN_DISCARDS, item),
          counter(values, IF_OUT_DISCARDS, item),
          now);
      interfaces.save(item);
    }
    if (!measures.isEmpty()) {
      ingestion.ingest(known.get(0).getEquipment().getId(), measures);
    }
  }

  private Map<String, BigDecimal> readCounters(Target target, List<EquipmentInterface> watched)
      throws SnmpFailure {
    var oids = new ArrayList<String>();
    for (EquipmentInterface item : watched) {
      for (String column : COUNTER_COLUMNS) {
        oids.add(column + "." + item.getIfIndex());
      }
    }
    var values = new HashMap<String, BigDecimal>();
    for (int from = 0; from < oids.size(); from += OIDS_PER_REQUEST) {
      List<String> chunk = oids.subList(from, Math.min(from + OIDS_PER_REQUEST, oids.size()));
      List<Reading> readings = client.get(target, chunk);
      for (int i = 0; i < chunk.size() && i < readings.size(); i++) {
        Reading reading = readings.get(i);
        if (reading.isNumeric()) {
          values.put(chunk.get(i), reading.number());
        }
      }
    }
    return values;
  }

  /** Mesures d'une interface : état, débits, erreurs, rejets et occupation du lien. */
  private List<MeasureInput> measuresFor(
      EquipmentInterface item, Map<String, BigDecimal> values, Instant now) {
    String instance = instanceKey(item.getName());
    var measures = new ArrayList<MeasureInput>();
    BigDecimal oper = values.get(IF_OPER_STATUS + "." + item.getIfIndex());
    if (oper != null) {
      boolean up = oper.intValue() == 1;
      item.setOperUp(up);
      measures.add(
          new MeasureInput(
              "net.up[" + instance + "]",
              up ? BigDecimal.ONE : BigDecimal.ZERO,
              "state",
              "GOOD",
              now));
    }
    BigDecimal in =
        rate(item.getInOctets(), counter(values, IF_HC_IN_OCTETS, item), item.getCountersAt(), now);
    BigDecimal out =
        rate(
            item.getOutOctets(),
            counter(values, IF_HC_OUT_OCTETS, item),
            item.getCountersAt(),
            now);
    add(measures, "net.in[" + instance + "]", in, "o/s", now);
    add(measures, "net.out[" + instance + "]", out, "o/s", now);

    BigDecimal errors =
        sum(
            rate(
                item.getInErrors(), counter(values, IF_IN_ERRORS, item), item.getCountersAt(), now),
            rate(
                item.getOutErrors(),
                counter(values, IF_OUT_ERRORS, item),
                item.getCountersAt(),
                now));
    add(measures, "net.errors[" + instance + "]", errors, "/s", now);

    BigDecimal discards =
        sum(
            rate(
                item.getInDiscards(),
                counter(values, IF_IN_DISCARDS, item),
                item.getCountersAt(),
                now),
            rate(
                item.getOutDiscards(),
                counter(values, IF_OUT_DISCARDS, item),
                item.getCountersAt(),
                now));
    add(measures, "net.discards[" + instance + "]", discards, "/s", now);

    // Occupation du lien : le sens le plus chargé, c'est lui qui sature en premier.
    BigDecimal usage =
        max(
            InterfaceRate.usagePercent(in, item.getSpeedBps()),
            InterfaceRate.usagePercent(out, item.getSpeedBps()));
    add(measures, "net.usage[" + instance + "]", usage, "%", now);
    return measures;
  }

  private static void add(
      List<MeasureInput> measures, String key, BigDecimal value, String unit, Instant now) {
    if (value != null) {
      measures.add(new MeasureInput(key, value, unit, "GOOD", now));
    }
  }

  private static BigDecimal rate(
      BigInteger previous, BigInteger current, Instant previousAt, Instant now) {
    return InterfaceRate.perSecond(previous, previousAt, current, now);
  }

  private static BigDecimal sum(BigDecimal left, BigDecimal right) {
    if (left == null) {
      return right;
    }
    return right == null ? left : left.add(right);
  }

  private static BigDecimal max(BigDecimal left, BigDecimal right) {
    if (left == null) {
      return right;
    }
    return right == null ? left : left.max(right);
  }

  private static BigInteger counter(
      Map<String, BigDecimal> values, String column, EquipmentInterface item) {
    BigDecimal value = values.get(column + "." + item.getIfIndex());
    return value == null ? null : value.toBigInteger();
  }

  /**
   * Nom d'instance d'une clé de mesure : les caractères qui gêneraient la lecture d'une clé {@code
   * famille[instance]} sont remplacés, et le nom est raccourci pour tenir dans la clé.
   */
  static String instanceKey(String name) {
    if (name == null || name.isBlank()) {
      return "sans-nom";
    }
    // Une suite de caractères gênants (espaces, crochets) devient un seul « _ ».
    String cleaned = name.trim().replaceAll("[^A-Za-z0-9._/:-]+", "_");
    // « net.discards[ ] » fait 16 caractères ; la clé entière doit tenir en 64.
    return cleaned.length() > 44 ? cleaned.substring(0, 44) : cleaned;
  }

  private static String shortened(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  /** Valeurs textuelles d'une colonne, par indice d'interface. */
  private static Map<Integer, String> text(List<Reading> readings) {
    var values = new LinkedHashMap<Integer, String>();
    for (Reading reading : readings) {
      Integer index = index(reading.oid());
      if (index != null && reading.text() != null && !reading.text().isBlank()) {
        values.put(index, reading.text());
      }
    }
    return values;
  }

  /** Valeurs numériques d'une colonne, par indice d'interface. */
  private static Map<Integer, BigDecimal> numbers(List<Reading> readings) {
    var values = new LinkedHashMap<Integer, BigDecimal>();
    for (Reading reading : readings) {
      Integer index = index(reading.oid());
      if (index != null && reading.isNumeric()) {
        values.put(index, reading.number());
      }
    }
    return values;
  }

  /** Dernier nombre de l'OID : l'indice de l'interface dans la table. */
  private static Integer index(String oid) {
    if (oid == null) {
      return null;
    }
    int dot = oid.lastIndexOf('.');
    try {
      return dot < 0 ? null : Integer.valueOf(oid.substring(dot + 1));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
