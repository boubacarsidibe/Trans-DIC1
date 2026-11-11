package sn.ept.transdic1.collection.template;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.ValueType;

/**
 * Règles de validité d'un modèle d'équipement. Elles reprennent celles des API qu'un modèle
 * alimente (collecte SNMP, règles d'alerte, sondes) : un modèle valide ne peut pas échouer à
 * l'application pour une raison de forme.
 */
final class EquipmentTemplateValidator {
  static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]*");
  static final Pattern OID = Pattern.compile("[0-9]+(\\.[0-9]+)+");
  static final Set<String> PROBE_KEYS =
      Set.of("probe.up", "probe.latency", "probe.loss", "probe.jitter", "probe.tls.days_left");
  static final Set<ServiceType> PROBE_TYPES =
      Set.of(
          ServiceType.TCP_PORT,
          ServiceType.HTTP,
          ServiceType.HTTPS,
          ServiceType.ICMP,
          ServiceType.TLS_CERT);

  /** Longueur maximale d'un nom de règle : la place reste pour « — code d'inventaire ». */
  static final int MAX_RULE_NAME = 80;

  static final int MAX_METRICS = 50;
  static final int MAX_PROBES = 50;
  private static final BigDecimal MAX_FACTOR = new BigDecimal("1000000");

  private EquipmentTemplateValidator() {}

  static void validate(EquipmentTemplate t, String file) {
    Checker c = new Checker(file);
    c.require(
        t.id() != null && t.id().length() <= 64 && SLUG.matcher(t.id()).matches(),
        "id doit être en minuscules, chiffres et tirets (64 caractères au plus)");
    String where = "modèle " + t.id() + " : ";
    c.require(t.version() != null && t.version() >= 1, where + "version entière >= 1 attendue");
    c.require(text(t.name(), 120), where + "name obligatoire (120 caractères au plus)");
    c.require(text(t.vendor(), 80), where + "vendor obligatoire (80 caractères au plus)");
    c.require(t.category() != null, where + "category obligatoire");
    c.require(text(t.description(), 1000), where + "description obligatoire");
    c.require(t.verified() != null, where + "verified obligatoire (true ou false)");
    c.require(
        !t.metrics().isEmpty() || !t.probes().isEmpty(),
        where + "aucun indicateur ni aucune sonde : rien à appliquer (modèle vide)");
    c.require(t.metrics().size() <= MAX_METRICS, where + MAX_METRICS + " indicateurs au plus");
    c.require(t.probes().size() <= MAX_PROBES, where + MAX_PROBES + " sondes au plus");
    for (String hint : t.sysDescrHints()) {
      c.require(text(hint, 120), where + "sysDescrHints : fragment vide");
    }

    Set<String> keys = new HashSet<>();
    for (var m : t.metrics()) {
      String key = m.technicalKey();
      String at = where + "indicateur " + key + " : ";
      c.require(
          key != null && key.length() <= 64 && KEY.matcher(key).matches(),
          where + "technicalKey invalide « " + key + " »");
      c.require(keys.add(key), where + "clé d'indicateur en double « " + key + " »");
      c.require(text(m.name(), 120), at + "name obligatoire");
      c.require(m.valueType() != null, at + "valueType obligatoire");
      c.require(m.valueType() != ValueType.TEXT, at + "valueType TEXT non pris en charge");
      c.require(m.unit() == null || m.unit().length() <= 24, at + "unit : 24 caractères au plus");
      c.require(
          m.snmpOid() != null && OID.matcher(m.snmpOid()).matches(),
          at + "OID numérique sans point initial attendu, reçu « " + m.snmpOid() + " »");
      c.require(text(m.source(), 255), at + "source (objet de MIB) obligatoire");
      c.require(
          m.scale() == null || (m.scale().signum() > 0 && m.scale().compareTo(MAX_FACTOR) <= 0),
          at + "scale doit être strictement positif (1 000 000 au plus)");
      c.require(
          m.scale() == null || m.scale().stripTrailingZeros().scale() <= 10,
          at + "scale : 10 décimales au plus (précision de la colonne metric_definition.scale)");
      c.require(
          m.offset() == null || m.offset().stripTrailingZeros().scale() <= 4,
          at + "offset : 4 décimales au plus");
      c.require(
          m.offset() == null || m.offset().abs().compareTo(MAX_FACTOR) <= 0,
          at + "offset : 1 000 000 au plus en valeur absolue");
    }

    Set<String> probeNames = new HashSet<>();
    for (var p : t.probes()) {
      String at = where + "sonde " + p.name() + " : ";
      c.require(text(p.name(), 120), where + "nom de sonde obligatoire");
      c.require(probeNames.add(p.name()), where + "nom de sonde en double « " + p.name() + " »");
      c.require(
          p.type() != null && PROBE_TYPES.contains(p.type()),
          at + "type " + p.type() + " non pris en charge (TCP_PORT, HTTP, HTTPS, ICMP, TLS_CERT)");
      c.require(
          p.type() != ServiceType.TCP_PORT || p.port() != null,
          at + "port obligatoire pour TCP_PORT");
      c.require(p.type() != ServiceType.ICMP || p.port() == null, at + "pas de port pour ICMP");
      c.require(
          p.port() == null || (p.port() >= 1 && p.port() <= 65_535), at + "port entre 1 et 65535");
      c.require(
          p.interval() >= 30 && p.interval() <= 86_400,
          at + "intervalle entre 30 et 86400 secondes");
    }

    Set<String> ruleNames = new HashSet<>();
    for (var r : t.rules()) {
      String at = where + "règle " + r.name() + " : ";
      c.require(
          text(r.name(), MAX_RULE_NAME),
          where + "nom de règle obligatoire (" + MAX_RULE_NAME + " caractères au plus)");
      c.require(ruleNames.add(r.name()), where + "nom de règle en double « " + r.name() + " »");
      boolean known =
          keys.contains(r.metricKey())
              || (!t.probes().isEmpty() && PROBE_KEYS.contains(r.metricKey()));
      c.require(
          known,
          at
              + "indicateur « "
              + r.metricKey()
              + " » absent du modèle (ou sans sonde pour probe.*)");
      c.require(r.operator() != null, at + "operator obligatoire");
      c.require(r.threshold() != null, at + "threshold obligatoire");
      c.require(r.severity() != null, at + "severity obligatoire");
      c.require(r.unit() == null || r.unit().length() <= 24, at + "unit : 24 caractères au plus");
      c.require(
          r.confirmationSeconds() != null
              && r.confirmationSeconds() >= 0
              && r.confirmationSeconds() <= 86_400,
          at + "confirmationSeconds entre 0 et 86400");
      if (r.recoveryThreshold() != null && r.operator() != null && r.threshold() != null) {
        int cmp = r.recoveryThreshold().compareTo(r.threshold());
        boolean valid =
            switch (r.operator()) {
              case GREATER_THAN, GREATER_OR_EQUAL -> cmp <= 0;
              case LESS_THAN, LESS_OR_EQUAL -> cmp >= 0;
              case EQUAL -> false;
            };
        c.require(valid, at + "seuil de retour du mauvais côté du seuil d'ouverture");
      }
    }

    Set<String> eventNames = new HashSet<>();
    for (var e : t.eventRules()) {
      String at = where + "règle d'événement " + e.name() + " : ";
      c.require(text(e.name(), 120), where + "nom de règle d'événement obligatoire");
      c.require(eventNames.add(e.name()), at + "nom en double");
      c.require(text(e.description(), 500), at + "description obligatoire");
      c.require(
          e.oidPrefix() == null || OID.matcher(e.oidPrefix()).matches(),
          at + "préfixe d'OID invalide");
      c.require(
          e.messageRegex() == null || e.messageRegex().length() <= 200,
          at + "expression : 200 caractères au plus");
      c.require(
          e.oidPrefix() != null || e.messageRegex() != null,
          at + "préfixe d'OID ou expression attendu");
      c.require(e.severity() != null, at + "severity obligatoire");
    }
  }

  private static boolean text(String value, int max) {
    return value != null && !value.isBlank() && value.length() <= max;
  }

  private record Checker(String file) {
    void require(boolean condition, String reason) {
      if (!condition) {
        throw EquipmentTemplateCatalog.invalid(file, reason);
      }
    }
  }
}
