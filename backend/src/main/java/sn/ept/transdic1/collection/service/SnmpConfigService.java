package sn.ept.transdic1.collection.service;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.SnmpConfigRequest;
import sn.ept.transdic1.collection.dto.SnmpConfigResponse;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.inventory.domain.CollectionConfiguration;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.MetricDefinition;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.domain.ValueType;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MetricDefinitionRepository;

/**
 * Configuration de la collecte SNMP d'un équipement (une seule par équipement). Les indicateurs
 * sont mis à jour par clé : un indicateur retiré est désactivé et non supprimé, car des mesures
 * existantes y font référence.
 */
@Service
public class SnmpConfigService {
  private final EquipmentRepository equipments;
  private final CollectionConfigurationRepository configurations;
  private final MetricDefinitionRepository definitions;
  private final AuditService audit;
  private final Clock clock;

  public SnmpConfigService(
      EquipmentRepository equipments,
      CollectionConfigurationRepository configurations,
      MetricDefinitionRepository definitions,
      AuditService audit,
      Clock clock) {
    this.equipments = equipments;
    this.configurations = configurations;
    this.definitions = definitions;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public SnmpConfigResponse get(UUID equipmentId) {
    return toResponse(find(equipmentId));
  }

  @Transactional
  public SnmpConfigResponse put(UUID equipmentId, SnmpConfigRequest request) {
    var equipment =
        equipments
            .findById(equipmentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement introuvable"));
    validate(request);

    var existing =
        configurations.findByEquipmentId(equipmentId).stream()
            .filter(c -> c.getMode() == CollectionMode.SNMP)
            .findFirst();
    var configuration =
        existing.orElseGet(
            () ->
                new CollectionConfiguration(
                    equipment,
                    request.name(),
                    CollectionMode.SNMP,
                    Duration.ofSeconds(request.pollIntervalSeconds()),
                    Duration.ofSeconds(request.timeoutSeconds()),
                    request.retryCount()));
    configuration.setName(request.name());
    configuration.setPollInterval(Duration.ofSeconds(request.pollIntervalSeconds()));
    configuration.setTimeout(Duration.ofSeconds(request.timeoutSeconds()));
    configuration.setRetryCount(request.retryCount());
    configuration.setSnmpVersion(request.snmpVersion());
    boolean v3 = request.snmpVersion() == SnmpVersion.V3;
    configuration.setCommunityReference(v3 ? null : request.communityReference());
    configuration.setSecurityIdentity(v3 ? request.securityIdentity() : null);
    configuration.setSecretReference(v3 ? request.secretReference() : null);
    configuration.setActive(request.active() == null || request.active());
    if (existing.isEmpty() || configuration.getNextRunAt() == null) {
      // Première lecture à la prochaine passe du planificateur.
      configuration.setNextRunAt(clock.instant());
    }
    configurations.saveAndFlush(configuration);
    syncMetrics(configuration, request.metrics());
    audit.record(
        "COLLECTION_CONFIGURED",
        "EQUIPMENT",
        equipmentId,
        "mode=SNMP version=" + request.snmpVersion() + " indicateurs=" + request.metrics().size());
    return toResponse(configuration);
  }

  @Transactional
  public void delete(UUID equipmentId) {
    var configuration = find(equipmentId);
    // Les définitions restent (des mesures y renvoient) : la collecte est simplement arrêtée.
    configuration.setActive(false);
    configurations.save(configuration);
    audit.record("COLLECTION_DISABLED", "EQUIPMENT", equipmentId, "mode=SNMP");
  }

  // ------------------------------------------------------------------ outils

  private void validate(SnmpConfigRequest request) {
    if (request.snmpVersion() == SnmpVersion.V2C) {
      require(
          SecretResolver.isSupported(request.communityReference()),
          "La communauté SNMP v2c doit être une référence de la forme env:NOM");
      require(
          request.securityIdentity() == null && request.secretReference() == null,
          "L'identité et le secret v3 n'ont pas de sens en SNMP v2c");
    } else {
      require(
          request.securityIdentity() != null && !request.securityIdentity().isBlank(),
          "L'identité SNMPv3 est obligatoire");
      require(
          SecretResolver.isSupported(request.secretReference()),
          "Le secret SNMPv3 doit être une référence de la forme env:NOM");
      require(request.communityReference() == null, "La communauté n'a pas de sens en SNMP v3");
    }
    Set<String> keys = new HashSet<>();
    for (var metric : request.metrics()) {
      require(
          metric.valueType() != ValueType.TEXT,
          "Les indicateurs textuels ne sont pas pris en charge");
      require(keys.add(metric.key()), "Clé d'indicateur en double : " + metric.key());
    }
  }

  private void syncMetrics(
      CollectionConfiguration configuration, List<SnmpConfigRequest.Metric> wanted) {
    var current = definitions.findByConfigurationId(configuration.getId());
    Set<String> wantedKeys = new HashSet<>();
    for (var metric : wanted) {
      wantedKeys.add(metric.key());
      var definition =
          current.stream()
              .filter(d -> d.getTechnicalKey().equals(metric.key()))
              .findFirst()
              .orElseGet(
                  () ->
                      new MetricDefinition(
                          configuration, metric.name(), metric.key(), metric.valueType()));
      definition.setName(metric.name());
      definition.setValueType(metric.valueType());
      definition.setUnit(metric.unit() == null || metric.unit().isBlank() ? null : metric.unit());
      definition.setSnmpOid(
          metric.oid().startsWith(".") ? metric.oid().substring(1) : metric.oid());
      definition.setActive(true);
      definitions.save(definition);
    }
    for (var definition : current) {
      if (!wantedKeys.contains(definition.getTechnicalKey())) {
        definition.setActive(false);
        definitions.save(definition);
      }
    }
  }

  private CollectionConfiguration find(UUID equipmentId) {
    return configurations.findByEquipmentId(equipmentId).stream()
        .filter(c -> c.getMode() == CollectionMode.SNMP)
        .findFirst()
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Aucune collecte SNMP configurée pour cet équipement"));
  }

  private SnmpConfigResponse toResponse(CollectionConfiguration c) {
    var metrics =
        definitions.findByConfigurationId(c.getId()).stream()
            .map(
                d ->
                    new SnmpConfigResponse.Metric(
                        d.getTechnicalKey(),
                        d.getName(),
                        d.getSnmpOid(),
                        d.getUnit(),
                        d.getValueType(),
                        d.isActive()))
            .toList();
    return new SnmpConfigResponse(
        c.getId(),
        c.getName(),
        c.getSnmpVersion(),
        c.getCommunityReference(),
        c.getSecurityIdentity(),
        c.getSecretReference(),
        (int) c.getPollInterval().toSeconds(),
        (int) c.getTimeout().toSeconds(),
        c.getRetryCount(),
        c.isActive(),
        c.getLastRunAt(),
        c.getNextRunAt(),
        metrics);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
  }
}
