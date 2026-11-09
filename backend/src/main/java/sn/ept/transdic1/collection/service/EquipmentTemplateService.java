package sn.ept.transdic1.collection.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.dto.AlertRuleRequest;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.RuleEquipmentScopeRepository;
import sn.ept.transdic1.alerting.service.AlertRuleService;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.ProbeRequestDto;
import sn.ept.transdic1.collection.dto.TemplateDtos.AppliedTemplateResponse;
import sn.ept.transdic1.collection.dto.TemplateDtos.DiffItem;
import sn.ept.transdic1.collection.dto.TemplateDtos.ItemAction;
import sn.ept.transdic1.collection.dto.TemplateDtos.Options;
import sn.ept.transdic1.collection.dto.TemplateDtos.SnmpConfigurationAction;
import sn.ept.transdic1.collection.dto.TemplateDtos.TemplateApplyRequest;
import sn.ept.transdic1.collection.dto.TemplateDtos.TemplateApplyResponse;
import sn.ept.transdic1.collection.dto.TemplateDtos.TemplateSummaryResponse;
import sn.ept.transdic1.collection.template.EquipmentTemplate;
import sn.ept.transdic1.collection.template.EquipmentTemplateCatalog;
import sn.ept.transdic1.collection.template.TemplateCategory;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.inventory.domain.CollectionConfiguration;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentTemplateApplication;
import sn.ept.transdic1.inventory.domain.MetricDefinition;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.EquipmentTemplateRepository;
import sn.ept.transdic1.inventory.repository.MetricDefinitionRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;

/**
 * Bibliothèque de modèles d'équipement (ADR 0022) : catalogue en lecture, aperçu et application.
 *
 * <p>L'aperçu et l'application passent par le même calcul ({@link #plan}) : ce que l'aperçu annonce
 * est exactement ce que l'application fait. L'application est idempotente — un indicateur est
 * reconnu à sa clé technique, une règle à son nom (suffixé du code d'inventaire et limitée à
 * l'équipement), une sonde à son nom sur l'équipement — et tout se fait dans une transaction. Les
 * règles et les sondes passent par les services existants (validation, garde des cibles, limite de
 * 50 sondes actives, audit), jamais contournés.
 */
@Service
public class EquipmentTemplateService {
  static final int MAX_METRICS_PER_CONFIGURATION = 50;
  static final int MAX_ACTIVE_PROBES = 50;
  private static final String EQUIPMENT_NOT_FOUND = "Équipement introuvable";
  private static final String TEMPLATE_NOT_FOUND = "Modèle introuvable";
  private static final String RULE_SEPARATOR = " — ";

  private final EquipmentTemplateCatalog catalog;
  private final EquipmentRepository equipments;
  private final CollectionConfigurationRepository configurations;
  private final MetricDefinitionRepository definitions;
  private final MonitoredServiceRepository services;
  private final EquipmentTemplateRepository applications;
  private final AlertRuleRepository rules;
  private final RuleEquipmentScopeRepository ruleScopes;
  private final AlertRuleService ruleService;
  private final ProbeAdminService probeService;
  private final AuditService audit;
  private final SiteScope scope;
  private final Clock clock;

  public EquipmentTemplateService(
      EquipmentTemplateCatalog catalog,
      EquipmentRepository equipments,
      CollectionConfigurationRepository configurations,
      MetricDefinitionRepository definitions,
      MonitoredServiceRepository services,
      EquipmentTemplateRepository applications,
      AlertRuleRepository rules,
      RuleEquipmentScopeRepository ruleScopes,
      AlertRuleService ruleService,
      ProbeAdminService probeService,
      AuditService audit,
      SiteScope scope,
      Clock clock) {
    this.catalog = catalog;
    this.equipments = equipments;
    this.configurations = configurations;
    this.definitions = definitions;
    this.services = services;
    this.applications = applications;
    this.rules = rules;
    this.ruleScopes = ruleScopes;
    this.ruleService = ruleService;
    this.probeService = probeService;
    this.audit = audit;
    this.scope = scope;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ catalogue

  public List<TemplateSummaryResponse> list(
      TemplateCategory category, String vendor, String q, String sysDescr) {
    return catalog.search(category, vendor, q, sysDescr).stream()
        .map(EquipmentTemplateService::summary)
        .toList();
  }

  public EquipmentTemplate get(String id) {
    return template(id);
  }

  @Transactional(readOnly = true)
  public List<AppliedTemplateResponse> applied(UUID equipmentId) {
    equipment(equipmentId);
    return applications.findByEquipmentIdOrderByAppliedAtDesc(equipmentId).stream()
        .map(
            a -> {
              Optional<EquipmentTemplate> current = catalog.find(a.getTemplateId());
              return new AppliedTemplateResponse(
                  a.getTemplateId(),
                  current.map(EquipmentTemplate::name).orElse(a.getTemplateId()),
                  a.getTemplateVersion(),
                  current.map(EquipmentTemplate::version).orElse(null),
                  a.getAppliedAt(),
                  a.getAppliedBy());
            })
        .toList();
  }

  // ------------------------------------------------------------------ aperçu et application

  /** Différentiel sans rien modifier. */
  @Transactional(readOnly = true)
  public TemplateApplyResponse preview(UUID equipmentId, TemplateApplyRequest request) {
    return plan(equipmentId, request).response(false);
  }

  /** Applique le modèle : tout ou rien, dans une seule transaction. */
  @Transactional
  public TemplateApplyResponse apply(UUID equipmentId, TemplateApplyRequest request) {
    Plan plan = plan(equipmentId, request);
    Equipment equipment = plan.equipment;
    EquipmentTemplate template = plan.template;

    if (plan.snmp == SnmpConfigurationAction.CREATED || plan.hasCreate(plan.metrics)) {
      CollectionConfiguration configuration =
          plan.configuration != null ? plan.configuration : newConfiguration(equipment, plan);
      Map<String, EquipmentTemplate.Metric> byKey =
          template.metrics().stream()
              .collect(
                  Collectors.toMap(EquipmentTemplate.Metric::technicalKey, Function.identity()));
      for (DiffItem item : plan.metrics) {
        if (item.action() == ItemAction.CREATE) {
          definitions.save(definition(configuration, byKey.get(item.key())));
        }
      }
      definitions.flush();
    }
    for (int i = 0; i < plan.rules.size(); i++) {
      if (plan.rules.get(i).action() == ItemAction.CREATE) {
        EquipmentTemplate.Rule rule = template.rules().get(i);
        ruleService.create(
            new AlertRuleRequest(
                plan.rules.get(i).name(),
                rule.metricKey(),
                rule.operator(),
                rule.threshold(),
                rule.recoveryThreshold(),
                rule.unit(),
                rule.confirmationSeconds(),
                rule.severity(),
                plan.activateRules,
                null,
                List.of(equipment.getId())));
      }
    }
    for (int i = 0; i < plan.probes.size(); i++) {
      if (plan.probes.get(i).action() == ItemAction.CREATE) {
        EquipmentTemplate.Probe probe = template.probes().get(i);
        probeService.create(
            equipment.getId(),
            new ProbeRequestDto(
                probe.name(),
                probe.type(),
                probe.port(),
                null,
                probe.interval(),
                null,
                null,
                null,
                true));
      }
    }

    String user = currentUsername();
    EquipmentTemplateApplication application =
        applications
            .findByEquipmentIdAndTemplateId(equipment.getId(), template.id())
            .orElseGet(() -> new EquipmentTemplateApplication(equipment, template.id()));
    application.record(template.version(), clock.instant(), user);
    applications.save(application);
    audit.record(
        "TEMPLATE_APPLIED",
        "EQUIPMENT",
        equipment.getId(),
        "modele="
            + template.id()
            + " version="
            + template.version()
            + " snmp="
            + plan.snmp
            + " indicateurs="
            + plan.count(plan.metrics)
            + " regles="
            + plan.count(plan.rules)
            + " sondes="
            + plan.count(plan.probes)
            + " reglesActives="
            + plan.activateRules);
    return plan.response(true);
  }

  // ------------------------------------------------------------------ calcul du différentiel

  private Plan plan(UUID equipmentId, TemplateApplyRequest request) {
    Equipment equipment = equipment(equipmentId);
    EquipmentTemplate template = template(request.templateId());
    Options options = request.options();
    boolean createRules = options == null || options.createRules() == null || options.createRules();
    boolean activateRules = options != null && Boolean.TRUE.equals(options.activateRules());
    boolean createProbes =
        options == null || options.createProbes() == null || options.createProbes();

    Plan plan = new Plan(equipment, template, activateRules);

    // Indicateurs SNMP.
    if (template.metrics().isEmpty()) {
      plan.snmp = SnmpConfigurationAction.NOT_NEEDED;
    } else {
      requireUsableAddress(equipment);
      plan.configuration =
          configurations.findByEquipmentId(equipmentId).stream()
              .filter(c -> c.getMode() == CollectionMode.SNMP)
              .findFirst()
              .orElse(null);
      List<MetricDefinition> existing = List.of();
      if (plan.configuration == null) {
        validateSnmpOptions(options);
        plan.options = options;
        plan.snmp = SnmpConfigurationAction.CREATED;
      } else {
        plan.snmp = SnmpConfigurationAction.EXISTING;
        existing = definitions.findByConfigurationId(plan.configuration.getId());
      }
      long active = existing.stream().filter(MetricDefinition::isActive).count();
      for (var metric : template.metrics()) {
        var found =
            existing.stream()
                .filter(d -> d.getTechnicalKey().equals(metric.technicalKey()))
                .findFirst();
        String detail = metric.snmpOid() + " (" + metric.source() + ")";
        if (found.isEmpty()) {
          plan.metrics.add(
              new DiffItem(metric.name(), metric.technicalKey(), detail, ItemAction.CREATE, null));
          active++;
        } else if (found.get().isActive()) {
          plan.metrics.add(
              new DiffItem(
                  metric.name(),
                  metric.technicalKey(),
                  detail,
                  ItemAction.PRESENT,
                  "Indicateur déjà collecté sous cette clé : il n'est pas modifié"));
        } else {
          plan.metrics.add(
              new DiffItem(
                  metric.name(),
                  metric.technicalKey(),
                  detail,
                  ItemAction.SKIPPED,
                  "Indicateur désactivé sur cet équipement : réactivez-le depuis la page Collecte"));
        }
      }
      if (active > MAX_METRICS_PER_CONFIGURATION) {
        throw badRequest(
            MAX_METRICS_PER_CONFIGURATION
                + " indicateurs actifs au maximum par équipement : ce modèle en ajouterait trop");
      }
    }

    // Règles d'alerte, limitées à l'équipement.
    for (var rule : template.rules()) {
      String name = ruleName(rule, equipment);
      String detail =
          rule.metricKey()
              + " "
              + rule.operator()
              + " "
              + rule.threshold().stripTrailingZeros().toPlainString()
              + (rule.unit() == null ? "" : " " + rule.unit())
              + ", "
              + rule.severity();
      if (!createRules) {
        plan.rules.add(
            new DiffItem(
                name,
                rule.metricKey(),
                detail,
                ItemAction.SKIPPED,
                "Création des règles non demandée"));
        continue;
      }
      var existing = rules.findByName(name);
      if (existing.isEmpty()) {
        plan.rules.add(new DiffItem(name, rule.metricKey(), detail, ItemAction.CREATE, null));
      } else if (ruleScopes.findByRuleId(existing.get().getId()).stream()
          .anyMatch(s -> s.getEquipment().getId().equals(equipmentId))) {
        plan.rules.add(
            new DiffItem(
                name,
                rule.metricKey(),
                detail,
                ItemAction.PRESENT,
                "Règle déjà créée pour cet équipement : elle n'est pas modifiée"));
      } else {
        plan.rules.add(
            new DiffItem(
                name,
                rule.metricKey(),
                detail,
                ItemAction.SKIPPED,
                "Une autre règle porte déjà ce nom : renommez-la pour appliquer celle-ci"));
      }
    }

    // Sondes, sur l'adresse de gestion.
    List<MonitoredService> existingServices = services.findByEquipmentIdOrderByName(equipmentId);
    Set<String> names = new HashSet<>();
    existingServices.forEach(s -> names.add(s.getName()));
    long activeProbes = services.countByEquipmentIdAndActiveTrue(equipmentId);
    for (var probe : template.probes()) {
      String detail =
          probe.type()
              + (probe.port() == null ? "" : " port " + probe.port())
              + ", toutes les "
              + probe.interval()
              + " s";
      if (!createProbes) {
        plan.probes.add(
            new DiffItem(
                probe.name(),
                probe.type().name(),
                detail,
                ItemAction.SKIPPED,
                "Création des sondes non demandée"));
      } else if (names.contains(probe.name())) {
        plan.probes.add(
            new DiffItem(
                probe.name(),
                probe.type().name(),
                detail,
                ItemAction.PRESENT,
                "Un service de ce nom existe déjà sur l'équipement : il n'est pas modifié"));
      } else {
        plan.probes.add(
            new DiffItem(probe.name(), probe.type().name(), detail, ItemAction.CREATE, null));
        activeProbes++;
      }
    }
    if (activeProbes > MAX_ACTIVE_PROBES) {
      throw badRequest(
          MAX_ACTIVE_PROBES
              + " sondes actives au maximum par équipement : ce modèle en ajouterait trop"
              + " (décochez « créer les sondes » ou libérez des sondes)");
    }

    // Règles d'événement : suggestions seulement (le module events reste indépendant).
    for (var event : template.eventRules()) {
      plan.eventRules.add(
          new DiffItem(
              event.name(),
              event.oidPrefix() != null ? event.oidPrefix() : event.messageRegex(),
              event.description(),
              ItemAction.SKIPPED,
              "Suggestion : à créer depuis la page Règles d'événements"));
    }
    return plan;
  }

  // ------------------------------------------------------------------ outils

  /** Le nom d'une règle d'alerte est unique : il porte le code d'inventaire de l'équipement. */
  static String ruleName(EquipmentTemplate.Rule rule, Equipment equipment) {
    String name = rule.name() + RULE_SEPARATOR + equipment.getInventoryCode();
    return name.length() <= 120 ? name : name.substring(0, 120);
  }

  private CollectionConfiguration newConfiguration(Equipment equipment, Plan plan) {
    Options o = plan.options;
    int poll = o.pollIntervalSeconds() == null ? 60 : o.pollIntervalSeconds();
    var configuration =
        new CollectionConfiguration(
            equipment,
            "SNMP (" + plan.template.name() + ")",
            CollectionMode.SNMP,
            Duration.ofSeconds(poll),
            Duration.ofSeconds(3),
            1);
    boolean v3 = o.snmpVersion() == SnmpVersion.V3;
    configuration.setSnmpVersion(o.snmpVersion());
    configuration.setCommunityReference(v3 ? null : o.communityReference());
    configuration.setSecurityIdentity(v3 ? o.securityIdentity().trim() : null);
    configuration.setSecretReference(v3 ? o.secretReference() : null);
    configuration.setSnmpPort(o.snmpPort());
    configuration.setNextRunAt(clock.instant());
    return configurations.saveAndFlush(configuration);
  }

  private static MetricDefinition definition(
      CollectionConfiguration configuration, EquipmentTemplate.Metric metric) {
    var definition =
        new MetricDefinition(
            configuration, metric.name(), metric.technicalKey(), metric.valueType());
    definition.setUnit(metric.unit() == null || metric.unit().isBlank() ? null : metric.unit());
    definition.setSnmpOid(metric.snmpOid());
    definition.setScale(metric.scale());
    definition.setValueOffset(metric.offset());
    definition.setInvertPercent(metric.invert());
    return definition;
  }

  /** Mêmes règles que la configuration SNMP : secrets uniquement par référence env:NOM. */
  private static void validateSnmpOptions(Options o) {
    if (o == null || o.snmpVersion() == null) {
      throw badRequest(
          "Cet équipement n'a pas encore de collecte SNMP : indiquez la version SNMP et la"
              + " référence de la communauté (env:NOM) ou du compte SNMPv3");
    }
    if (o.snmpVersion() == SnmpVersion.V2C) {
      require(
          SecretResolver.isSupported(o.communityReference()),
          "La communauté SNMP v2c doit être une référence de la forme env:NOM");
      require(
          isBlank(o.securityIdentity()) && isBlank(o.secretReference()),
          "L'identité et le secret v3 n'ont pas de sens en SNMP v2c");
    } else {
      require(!isBlank(o.securityIdentity()), "L'identité SNMPv3 est obligatoire");
      require(
          SecretResolver.isSupported(o.secretReference()),
          "Le secret SNMPv3 doit être une référence de la forme env:NOM");
      require(isBlank(o.communityReference()), "La communauté n'a pas de sens en SNMP v3");
    }
  }

  /** Adresse vide, joker (0.0.0.0, ::) ou contenant des espaces : aucune lecture SNMP possible. */
  private static void requireUsableAddress(Equipment equipment) {
    String address = equipment.getManagementAddress();
    String normalized = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    boolean usable =
        !normalized.isEmpty()
            && !normalized.contains(" ")
            && !normalized.equals("0.0.0.0")
            && !normalized.equals("::")
            && !normalized.equals("[::]");
    if (!usable) {
      throw badRequest(
          "L'équipement n'a pas d'adresse de gestion utilisable : impossible d'y lire des"
              + " indicateurs SNMP. Corrigez l'adresse dans l'inventaire.");
    }
  }

  private Equipment equipment(UUID equipmentId) {
    Equipment equipment =
        equipments
            .findById(equipmentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, EQUIPMENT_NOT_FOUND));
    scope.requireVisible(
        equipment.getSite() == null ? null : equipment.getSite().getId(), EQUIPMENT_NOT_FOUND);
    return equipment;
  }

  private EquipmentTemplate template(String id) {
    return catalog
        .find(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, TEMPLATE_NOT_FOUND));
  }

  private static TemplateSummaryResponse summary(EquipmentTemplate t) {
    return new TemplateSummaryResponse(
        t.id(),
        t.version(),
        t.name(),
        t.vendor(),
        t.category(),
        t.description(),
        t.verified(),
        t.metrics().size(),
        t.rules().size(),
        t.probes().size(),
        t.eventRules().size());
  }

  private static String currentUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null || !auth.isAuthenticated() ? null : auth.getName();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw badRequest(message);
    }
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** Résultat du calcul, partagé par l'aperçu et l'application. */
  private static final class Plan {
    final Equipment equipment;
    final EquipmentTemplate template;
    final boolean activateRules;
    SnmpConfigurationAction snmp;
    CollectionConfiguration configuration;
    Options options;
    final List<DiffItem> metrics = new ArrayList<>();
    final List<DiffItem> rules = new ArrayList<>();
    final List<DiffItem> probes = new ArrayList<>();
    final List<DiffItem> eventRules = new ArrayList<>();

    Plan(Equipment equipment, EquipmentTemplate template, boolean activateRules) {
      this.equipment = equipment;
      this.template = template;
      this.activateRules = activateRules;
    }

    boolean hasCreate(List<DiffItem> items) {
      return items.stream().anyMatch(i -> i.action() == ItemAction.CREATE);
    }

    long count(List<DiffItem> items) {
      return items.stream().filter(i -> i.action() == ItemAction.CREATE).count();
    }

    TemplateApplyResponse response(boolean applied) {
      return new TemplateApplyResponse(
          template.id(),
          template.version(),
          template.verified(),
          applied,
          snmp,
          List.copyOf(metrics),
          List.copyOf(rules),
          List.copyOf(probes),
          List.copyOf(eventRules));
    }
  }
}
