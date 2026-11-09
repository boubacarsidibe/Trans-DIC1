package sn.ept.transdic1.collection.template;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.ValueType;

/**
 * Modèle d'équipement (ADR 0022) : fichier JSON versionné sous {@code templates/equipment/}, lu et
 * validé au démarrage. Il ne contient jamais de secret : la communauté ou le compte SNMPv3 se
 * donnent à l'application, par référence {@code env:NOM}.
 */
@Schema(description = "Modèle d'équipement : indicateurs SNMP, règles et sondes suggérées")
public record EquipmentTemplate(
    @Schema(description = "Identifiant unique (minuscules et tirets)", example = "linux-net-snmp")
        String id,
    @Schema(description = "Version entière, augmentée à chaque modification du fichier")
        Integer version,
    String name,
    String vendor,
    TemplateCategory category,
    String description,
    @Schema(
            description =
                "Faux : OID tirés de la documentation publique des MIB, pas encore validés sur un"
                    + " matériel réel")
        Boolean verified,
    @Schema(description = "Fragments du sysDescr qui suggèrent ce modèle après une découverte")
        List<String> sysDescrHints,
    List<Metric> metrics,
    List<Rule> rules,
    List<Probe> probes,
    List<EventRuleSuggestion> eventRules) {

  public EquipmentTemplate {
    sysDescrHints = sysDescrHints == null ? List.of() : List.copyOf(sysDescrHints);
    metrics = metrics == null ? List.of() : List.copyOf(metrics);
    rules = rules == null ? List.of() : List.copyOf(rules);
    probes = probes == null ? List.of() : List.copyOf(probes);
    eventRules = eventRules == null ? List.of() : List.copyOf(eventRules);
  }

  /**
   * Indicateur lu sur un OID. Valeur stockée = brute × {@code scale} + {@code offset}, puis 100 - x
   * si {@code invertPercent}.
   */
  public record Metric(
      String name,
      String technicalKey,
      ValueType valueType,
      String unit,
      String snmpOid,
      @Schema(description = "Objet de MIB d'où vient l'OID, par exemple UCD-SNMP-MIB::ssCpuIdle.0")
          String source,
      BigDecimal scale,
      BigDecimal offset,
      Boolean invertPercent) {

    public boolean invert() {
      return Boolean.TRUE.equals(invertPercent);
    }
  }

  /** Règle d'alerte suggérée, limitée à l'équipement lors de l'application. */
  public record Rule(
      String name,
      String metricKey,
      ComparisonOperator operator,
      BigDecimal threshold,
      BigDecimal recoveryThreshold,
      String unit,
      Long confirmationSeconds,
      Severity severity) {}

  /** Sonde sans agent suggérée ; elle vise l'adresse de gestion de l'équipement. */
  public record Probe(String name, ServiceType type, Integer port, Integer intervalSeconds) {

    public int interval() {
      return intervalSeconds == null ? 60 : intervalSeconds;
    }
  }

  /**
   * Règle d'événement (trap, syslog) suggérée : jamais créée par l'application d'un modèle, elle se
   * crée depuis la page des règles d'événements.
   */
  public record EventRuleSuggestion(
      String name, String description, String oidPrefix, String messageRegex, Severity severity) {}
}
