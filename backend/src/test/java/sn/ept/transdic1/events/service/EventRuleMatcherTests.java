package sn.ept.transdic1.events.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.events.domain.EventRule;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;

/** Correspondance d'un événement avec une règle : OID, expression, sévérité, portée. */
class EventRuleMatcherTests {
  private static final UUID EQUIPMENT = UUID.randomUUID();
  private static final UUID SITE = UUID.randomUUID();
  private final EventRuleMatcher matcher = new EventRuleMatcher();

  private static EventRule rule() {
    return new EventRule("règle", Severity.WARNING);
  }

  private static EventFacts trap(String oid, EventSeverity severity) {
    return new EventFacts(EventSource.SNMP_TRAP, severity, oid, "ifIndex = 3", EQUIPMENT, SITE);
  }

  private static EventFacts syslog(String text, EventSeverity severity) {
    return new EventFacts(EventSource.SYSLOG, severity, null, text, EQUIPMENT, SITE);
  }

  @Test
  void préfixeDOidAvecFrontièreDeNœud() {
    var rule = rule();
    rule.setSource(EventSource.SNMP_TRAP);
    rule.setOidPrefix("1.3.6.1.6.3.1.1.5.3");

    assertThat(matcher.evaluate(rule, trap("1.3.6.1.6.3.1.1.5.3", EventSeverity.ERROR)).decision())
        .isEqualTo(EventRuleMatcher.Decision.OPEN);
    assertThat(
            matcher.evaluate(rule, trap("1.3.6.1.6.3.1.1.5.3.1", EventSeverity.ERROR)).decision())
        .isEqualTo(EventRuleMatcher.Decision.OPEN);
    // 1.3.6.1.6.3.1.1.5.30 n'est pas sous 1.3.6.1.6.3.1.1.5.3.
    assertThat(matcher.evaluate(rule, trap("1.3.6.1.6.3.1.1.5.30", EventSeverity.ERROR)).decision())
        .isEqualTo(EventRuleMatcher.Decision.NONE);
    assertThat(matcher.evaluate(rule, syslog("x", EventSeverity.ERROR)).decision())
        .isEqualTo(EventRuleMatcher.Decision.NONE);
  }

  @Test
  void unOidAvecPointInitialEstNormalisé() {
    var rule = rule();
    rule.setOidPrefix(".1.3.6.1.6.3.1.1.5");

    assertThat(
            matcher.evaluate(rule, trap("1.3.6.1.6.3.1.1.5.5", EventSeverity.WARNING)).decision())
        .isEqualTo(EventRuleMatcher.Decision.OPEN);
  }

  @Test
  void expressionInsensibleÀLaCasse() {
    var rule = rule();
    rule.setSource(EventSource.SYSLOG);
    rule.setMessageRegex("disk (full|plein)");

    assertThat(
            matcher
                .evaluate(rule, syslog("ALERTE : DISK FULL sur /var", EventSeverity.INFO))
                .decision())
        .isEqualTo(EventRuleMatcher.Decision.OPEN);
    assertThat(matcher.evaluate(rule, syslog("tout va bien", EventSeverity.INFO)).decision())
        .isEqualTo(EventRuleMatcher.Decision.NONE);
  }

  @Test
  void sévéritéMinimale() {
    var rule = rule();
    rule.setMinSeverity(EventSeverity.ERROR);

    assertThat(matcher.evaluate(rule, syslog("a", EventSeverity.CRITICAL)).decision())
        .isEqualTo(EventRuleMatcher.Decision.OPEN);
    assertThat(matcher.evaluate(rule, syslog("a", EventSeverity.ERROR)).decision())
        .isEqualTo(EventRuleMatcher.Decision.OPEN);
    assertThat(matcher.evaluate(rule, syslog("a", EventSeverity.WARNING)).decision())
        .isEqualTo(EventRuleMatcher.Decision.NONE);
  }

  @Test
  void portéeSiteOuÉquipement() {
    var rule = rule();
    rule.setMinSeverity(EventSeverity.DEBUG);
    rule.setSiteIds(Set.of(UUID.randomUUID()));
    assertThat(matcher.evaluate(rule, syslog("a", EventSeverity.ERROR)).decision())
        .isEqualTo(EventRuleMatcher.Decision.NONE);

    rule.setSiteIds(Set.of(SITE));
    assertThat(matcher.evaluate(rule, syslog("a", EventSeverity.ERROR)).decision())
        .isEqualTo(EventRuleMatcher.Decision.OPEN);

    rule.setSiteIds(Set.of());
    rule.setEquipmentIds(Set.of(EQUIPMENT));
    assertThat(matcher.evaluate(rule, syslog("a", EventSeverity.ERROR)).decision())
        .isEqualTo(EventRuleMatcher.Decision.OPEN);
  }

  @Test
  void conditionDeRésolutionIgnoreLaSévéritéMinimale() {
    var rule = rule();
    rule.setSource(EventSource.SNMP_TRAP);
    rule.setOidPrefix("1.3.6.1.6.3.1.1.5.3");
    rule.setMinSeverity(EventSeverity.ERROR);
    rule.setResolveOid("1.3.6.1.6.3.1.1.5.4");

    assertThat(matcher.evaluate(rule, trap("1.3.6.1.6.3.1.1.5.4", EventSeverity.NOTICE)).decision())
        .isEqualTo(EventRuleMatcher.Decision.RESOLVE);
  }

  @Test
  void uneExpressionCatastrophiqueEstInterrompue() {
    var rule = rule();
    rule.setMessageRegex("(a+)+\\1$");
    long start = System.nanoTime();

    var verdict = matcher.evaluate(rule, syslog("a".repeat(40) + "!", EventSeverity.INFO));

    assertThat(verdict.decision()).isEqualTo(EventRuleMatcher.Decision.NONE);
    assertThat(verdict.aborted()).isTrue();
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
  }

  @Test
  void validationDesExpressions() {
    assertThatThrownBy(() -> SafeRegex.compile("x".repeat(SafeRegex.MAX_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("200");
    assertThatThrownBy(() -> SafeRegex.compile("(abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalide");
    assertThatThrownBy(() -> SafeRegex.validate("(a+)+\\1$"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("coûteuse");
    assertThat(SafeRegex.compile("link (down|up)").matcher("LINK DOWN").find()).isTrue();
  }
}
