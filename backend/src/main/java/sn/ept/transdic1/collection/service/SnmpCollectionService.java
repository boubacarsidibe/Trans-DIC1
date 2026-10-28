package sn.ept.transdic1.collection.service;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.alerting.service.CollectionAlertService;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.SnmpClient.Reading;
import sn.ept.transdic1.collection.service.SnmpClient.SnmpFailure;
import sn.ept.transdic1.collection.service.SnmpClient.Target;
import sn.ept.transdic1.inventory.domain.CollectionConfiguration;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.MetricDefinition;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.domain.ValueType;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.MetricDefinitionRepository;

/**
 * Collecte SNMP planifiée : pour chaque configuration active arrivée à échéance, lit les OID de
 * l'équipement, dépose les mesures (qui déclenchent l'évaluation des alertes) et reprogramme la
 * prochaine lecture. Un équipement qui ne répond pas ouvre une alerte de collecte ; elle se résout
 * seule à la première mesure reçue. Une configuration en échec ne bloque jamais les autres.
 */
@Service
public class SnmpCollectionService {
  private static final Logger LOG = LoggerFactory.getLogger(SnmpCollectionService.class);

  private final CollectionConfigurationRepository configurations;
  private final MetricDefinitionRepository definitions;
  private final MeasureIngestionService ingestion;
  private final CollectionAlertService collectionAlerts;
  private final SnmpClient client;
  private final SecretResolver secrets;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final int port;
  private final ExecutorService executor;

  public SnmpCollectionService(
      CollectionConfigurationRepository configurations,
      MetricDefinitionRepository definitions,
      MeasureIngestionService ingestion,
      CollectionAlertService collectionAlerts,
      SnmpClient client,
      SecretResolver secrets,
      TransactionTemplate transaction,
      Clock clock,
      @Value("${collection.snmp.default-port:161}") int port,
      @Value("${collection.snmp.parallelism:8}") int parallelism) {
    this.configurations = configurations;
    this.definitions = definitions;
    this.ingestion = ingestion;
    this.collectionAlerts = collectionAlerts;
    this.client = client;
    this.secrets = secrets;
    this.transaction = transaction;
    this.clock = clock;
    this.port = port;
    // parallelism <= 1 : tout se passe dans le thread appelant (tests, petits parcs).
    this.executor = parallelism > 1 ? Executors.newFixedThreadPool(parallelism) : null;
  }

  @PreDestroy
  void shutdown() {
    if (executor != null) {
      executor.shutdown();
    }
  }

  /** Lit les équipements dont l'échéance est atteinte ; retourne le nombre de lectures faites. */
  @Scheduled(fixedDelayString = "${collection.snmp.scan-interval-ms:15000}")
  public int collectDue() {
    Instant now = clock.instant();
    List<UUID> due =
        transaction.execute(
            status ->
                configurations.findByActiveTrueAndNextRunAtLessThanEqual(now).stream()
                    .filter(c -> c.getMode() == CollectionMode.SNMP)
                    .map(CollectionConfiguration::getId)
                    .toList());
    if (due == null || due.isEmpty()) {
      return 0;
    }
    if (executor == null) {
      due.forEach(this::collectSafely);
    } else {
      var futures =
          due.stream()
              .map(id -> CompletableFuture.runAsync(() -> collectSafely(id), executor))
              .toList();
      futures.forEach(CompletableFuture::join);
    }
    return due.size();
  }

  private void collectSafely(UUID configurationId) {
    try {
      transaction.executeWithoutResult(status -> collectOne(configurationId));
    } catch (RuntimeException e) {
      // Ne jamais laisser une configuration défaillante arrêter la boucle des autres.
      LOG.error(
          "Collecte SNMP en erreur pour la configuration {} : {}", configurationId, e.toString());
    }
  }

  private void collectOne(UUID configurationId) {
    CollectionConfiguration configuration = configurations.findById(configurationId).orElse(null);
    if (configuration == null || !configuration.isActive()) {
      return;
    }
    Instant now = clock.instant();
    var equipment = configuration.getEquipment();
    try {
      if (equipment.isMonitoringEnabled()) {
        read(configuration);
      }
    } catch (SnmpFailure | RuntimeException e) {
      String reason =
          e instanceof SnmpFailure ? e.getMessage() : "configuration de collecte invalide";
      LOG.warn("Équipement {} injoignable en SNMP : {}", equipment.getId(), reason);
      collectionAlerts.reportFailure(
          equipment, "Équipement " + equipment.getName() + " injoignable en SNMP : " + reason, now);
    }
    configuration.setLastRunAt(now);
    configuration.setNextRunAt(now.plus(configuration.getPollInterval()));
    configurations.save(configuration);
  }

  private void read(CollectionConfiguration configuration) throws SnmpFailure {
    var equipment = configuration.getEquipment();
    List<MetricDefinition> metrics =
        definitions.findByConfigurationIdAndActiveTrue(configuration.getId()).stream()
            .filter(m -> m.getSnmpOid() != null && !m.getSnmpOid().isBlank())
            .filter(m -> m.getValueType() != ValueType.TEXT)
            .toList();
    if (metrics.isEmpty()) {
      throw new SnmpFailure("Aucun indicateur SNMP actif à lire");
    }
    List<Reading> readings =
        client.get(
            target(configuration, equipment.getManagementAddress()),
            metrics.stream().map(MetricDefinition::getSnmpOid).toList());

    Instant now = clock.instant();
    var measures = new ArrayList<MeasureInput>();
    for (int i = 0; i < metrics.size() && i < readings.size(); i++) {
      var definition = metrics.get(i);
      var reading = readings.get(i);
      if (!reading.isNumeric()) {
        LOG.warn(
            "Valeur non numérique ignorée pour {} sur {}",
            definition.getTechnicalKey(),
            equipment.getId());
        continue;
      }
      String unit = definition.getUnit() == null ? "" : definition.getUnit();
      measures.add(
          new MeasureInput(definition.getTechnicalKey(), reading.number(), unit, "GOOD", now));
    }
    if (measures.isEmpty()) {
      throw new SnmpFailure("Aucune valeur numérique exploitable dans la réponse");
    }
    ingestion.ingest(equipment.getId(), measures);
  }

  private Target target(CollectionConfiguration configuration, String host) {
    int retries = configuration.getRetryCount() == null ? 0 : configuration.getRetryCount();
    if (configuration.getSnmpVersion() == SnmpVersion.V3) {
      String secret = secrets.resolve(configuration.getSecretReference());
      String[] parts = secret.split("\\|", 2);
      return new Target(
          host,
          port,
          SnmpVersion.V3,
          null,
          configuration.getSecurityIdentity(),
          parts[0],
          parts.length > 1 && !parts[1].isBlank() ? parts[1] : null,
          configuration.getTimeout(),
          retries);
    }
    return new Target(
        host,
        port,
        SnmpVersion.V2C,
        secrets.resolve(configuration.getCommunityReference()),
        null,
        null,
        null,
        configuration.getTimeout(),
        retries);
  }
}
