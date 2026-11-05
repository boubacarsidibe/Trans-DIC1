package sn.ept.transdic1.collection.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.inventory.domain.ValueType;

/**
 * Chargement et validation des modèles d'équipement : un fichier invalide arrête le démarrage avec
 * un message qui nomme le fichier ; chaque modèle livré est valide.
 */
class EquipmentTemplateCatalogTests {
  private static final Pattern DOTTED_OID = Pattern.compile("[0-9]+(\\.[0-9]+)+");

  private static final String METRIC =
      """
      {"name":"Durée de fonctionnement","technicalKey":"system.uptime","valueType":"DECIMAL",
       "unit":"s","snmpOid":"1.3.6.1.2.1.1.3.0","source":"SNMPv2-MIB::sysUpTime.0","scale":0.01}
      """;

  private static String template(String id, String extra) {
    return """
        {"id":"%s","version":1,"name":"Modèle %s","vendor":"Générique","category":"GENERIC",
         "description":"Modèle de test","verified":true,
         "metrics":[%s]%s}
        """
        .formatted(id, id, METRIC, extra);
  }

  private static EquipmentTemplateCatalog load(String... files) {
    Map<String, String> sources = new LinkedHashMap<>();
    for (int i = 0; i < files.length; i++) {
      sources.put("modele-" + i + ".json", files[i]);
    }
    return EquipmentTemplateCatalog.fromSources(sources);
  }

  private static void assertRejected(String json, String... fragments) {
    var assertion =
        assertThatThrownBy(() -> load(json))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("modele-0.json");
    for (String fragment : fragments) {
      assertion.hasMessageContaining(fragment);
    }
  }

  @Test
  void aValidTemplateIsLoaded() {
    var catalog = load(template("hote-test", ""));

    assertThat(catalog.all()).hasSize(1);
    var loaded = catalog.find("hote-test").orElseThrow();
    assertThat(loaded.metrics()).hasSize(1);
    assertThat(loaded.metrics().get(0).scale()).isEqualByComparingTo("0.01");
    assertThat(loaded.rules()).isEmpty();
  }

  @Test
  void duplicateIdsAcrossFilesAreRejected() {
    assertThatThrownBy(() -> load(template("double", ""), template("double", "")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("modele-1.json")
        .hasMessageContaining("double");
  }

  @Test
  void aBadOidIsRejected() {
    assertRejected(template("oid", "").replace("1.3.6.1.2.1.1.3.0", "1.3.6.x"), "OID");
    assertRejected(template("oid", "").replace("1.3.6.1.2.1.1.3.0", ".1.3.6.1"), "OID");
  }

  @Test
  void anUnknownCategoryIsRejected() {
    assertRejected(template("categorie", "").replace("GENERIC", "TOASTER"), "category");
  }

  @Test
  void aNegativeOrZeroScaleIsRejected() {
    assertRejected(template("echelle", "").replace("\"scale\":0.01", "\"scale\":-2"), "scale");
    assertRejected(template("echelle", "").replace("\"scale\":0.01", "\"scale\":0"), "scale");
  }

  @Test
  void anUnknownFieldIsRejectedToCatchTypos() {
    assertRejected(template("faute", "").replace("\"scale\"", "\"scal\""), "scal");
  }

  @Test
  void badIdentifiersAndMissingFieldsAreRejected() {
    assertRejected(template("Pas Un Slug", ""), "id");
    assertRejected(template("version", "").replace("\"version\":1", "\"version\":0"), "version");
    assertRejected(template("verifie", "").replace(",\"verified\":true", ""), "verified");
    assertRejected(template("description", "").replace("Modèle de test", " "), "description");
  }

  @Test
  void duplicateMetricKeysAndTextMetricsAreRejected() {
    assertRejected(
        template("cles", "").replace("[" + METRIC + "]", "[" + METRIC + "," + METRIC + "]"),
        "system.uptime");
    assertRejected(template("texte", "").replace("DECIMAL", "TEXT"), "TEXT");
  }

  @Test
  void rulesMustTargetAKnownMetricWithAConsistentRecovery() {
    String unknownMetric =
        ",\"rules\":[{\"name\":\"R\",\"metricKey\":\"cpu.load\",\"operator\":\"GREATER_THAN\","
            + "\"threshold\":90,\"confirmationSeconds\":300,\"severity\":\"WARNING\"}]";
    assertRejected(template("regle", unknownMetric), "cpu.load");

    String badRecovery =
        ",\"rules\":[{\"name\":\"R\",\"metricKey\":\"system.uptime\",\"operator\":\"LESS_THAN\","
            + "\"threshold\":600,\"recoveryThreshold\":300,\"confirmationSeconds\":0,"
            + "\"severity\":\"INFO\"}]";
    assertRejected(template("retour", badRecovery), "retour");

    String probeRuleWithoutProbe =
        ",\"rules\":[{\"name\":\"R\",\"metricKey\":\"probe.up\",\"operator\":\"LESS_THAN\","
            + "\"threshold\":100,\"confirmationSeconds\":180,\"severity\":\"CRITICAL\"}]";
    assertRejected(template("sonde", probeRuleWithoutProbe), "probe.up");
  }

  @Test
  void probesRespectTypesPortsAndMinimumInterval() {
    assertRejected(
        template("tcp", ",\"probes\":[{\"name\":\"SSH\",\"type\":\"TCP_PORT\"}]"), "port");
    assertRejected(
        template(
            "intervalle",
            ",\"probes\":[{\"name\":\"Ping\",\"type\":\"ICMP\",\"intervalSeconds\":10}]"),
        "30");
    assertRejected(template("dns", ",\"probes\":[{\"name\":\"DNS\",\"type\":\"DNS\"}]"), "DNS");
    assertRejected(
        template(
            "noms",
            ",\"probes\":[{\"name\":\"Ping\",\"type\":\"ICMP\"},{\"name\":\"Ping\",\"type\":\"ICMP\"}]"),
        "Ping");
  }

  @Test
  void aTemplateWithoutAnythingToApplyIsRejected() {
    assertRejected(template("vide", "").replace("[" + METRIC + "]", "[]"), "vide");
  }

  @Test
  void malformedJsonNamesTheFile() {
    assertRejected("{\"id\":", "JSON");
  }

  @Test
  void filtersByCategoryVendorTextAndSysDescr() {
    var catalog =
        load(
            template("hote-a", ",\"sysDescrHints\":[\"Linux\"]"),
            template("hote-b", "").replace("GENERIC", "UPS").replace("Générique", "APC"));

    assertThat(catalog.search(TemplateCategory.UPS, null, null, null))
        .extracting(EquipmentTemplate::id)
        .containsExactly("hote-b");
    assertThat(catalog.search(null, "apc", null, null))
        .extracting(EquipmentTemplate::id)
        .containsExactly("hote-b");
    assertThat(catalog.search(null, null, "HOTE-A", null))
        .extracting(EquipmentTemplate::id)
        .containsExactly("hote-a");
    assertThat(catalog.search(null, null, null, "Linux srv01 5.15.0-91-generic x86_64"))
        .extracting(EquipmentTemplate::id)
        .containsExactly("hote-a");
  }

  // ------------------------------------------------------------------ modèles livrés

  @Test
  void everyBuiltInTemplateIsValid() throws IOException {
    var catalog = EquipmentTemplateCatalog.fromClasspath(EquipmentTemplateCatalog.DEFAULT_LOCATION);

    assertThat(catalog.all())
        .extracting(EquipmentTemplate::id)
        .contains(
            "generic-snmp-host",
            "linux-net-snmp",
            "windows-server-snmp",
            "mikrotik-routeros",
            "cisco-ios",
            "ubiquiti-unifi-ap",
            "hpe-aruba-switch",
            "apc-ups",
            "generic-printer",
            "https-web-service");
    for (var template : catalog.all()) {
      Set<String> keys = new HashSet<>();
      for (var metric : template.metrics()) {
        assertThat(metric.snmpOid()).as(template.id()).matches(DOTTED_OID);
        assertThat(metric.source()).as(template.id()).isNotBlank();
        assertThat(metric.valueType()).isNotEqualTo(ValueType.TEXT);
        assertThat(keys.add(metric.technicalKey()))
            .as(template.id() + " : clé " + metric.technicalKey())
            .isTrue();
      }
      assertThat(template.description()).isNotBlank();
    }
  }

  @Test
  void onlyPureMib2TemplatesAreMarkedVerified() throws IOException {
    var catalog = EquipmentTemplateCatalog.fromClasspath(EquipmentTemplateCatalog.DEFAULT_LOCATION);

    for (var template : catalog.all()) {
      boolean pureMib2 =
          !template.metrics().isEmpty()
              && template.metrics().stream()
                  .allMatch(
                      m ->
                          m.snmpOid().startsWith("1.3.6.1.2.1.1.")
                              || m.snmpOid().startsWith("1.3.6.1.2.1.2."));
      if (template.verified()) {
        assertThat(pureMib2).as(template.id() + " marqué vérifié sans être MIB-II pur").isTrue();
      }
    }
    assertThat(catalog.find("generic-snmp-host").orElseThrow().verified()).isTrue();
    assertThat(catalog.find("cisco-ios").orElseThrow().verified()).isFalse();
  }

  @Test
  void theHttpsServiceTemplateHasProbesOnly() throws IOException {
    var https =
        EquipmentTemplateCatalog.fromClasspath(EquipmentTemplateCatalog.DEFAULT_LOCATION)
            .find("https-web-service")
            .orElseThrow();

    assertThat(https.metrics()).isEmpty();
    assertThat(https.probes())
        .extracting(EquipmentTemplate.Probe::type)
        .extracting(Enum::name)
        .containsExactlyInAnyOrder("HTTPS", "TLS_CERT");
    assertThat(https.rules())
        .extracting(EquipmentTemplate.Rule::metricKey)
        .containsExactlyInAnyOrder("probe.up", "probe.tls.days_left");
  }
}
