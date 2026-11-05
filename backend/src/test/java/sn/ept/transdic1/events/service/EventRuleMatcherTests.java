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
  void lesMotifsPolynomiauxSontRefusésÀLEnregistrement() {
    assertThatThrownBy(() -> SafeRegex.validate(".*a.*b.*c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("coûteuse");
    assertThatThrownBy(() -> SafeRegex.validate("(\\w+\\s?)+:"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("coûteuse");
    // Les motifs usuels restent acceptés.
    SafeRegex.validate("power supply (fail|down)");
    SafeRegex.validate("link (down|up)");
    SafeRegex.validate("%LINK-3-UPDOWN: Interface \\S+, changed state to down");
  }

  @Test
  void leBudgetDExécutionEstDeLOrdreDUnMillionDAccès() {
    assertThat(SafeRegex.BUDGET).isLessThanOrEqualTo(1_000_000L);
    assertThat(SafeRegex.EVENT_BUDGET).isGreaterThanOrEqualTo(SafeRegex.BUDGET);
    var pattern = SafeRegex.compile(".*a.*b.*c");
    long start = System.nanoTime();
    assertThat(SafeRegex.find(pattern, "a".repeat(4096) + "!"))
        .isEqualTo(SafeRegex.Outcome.ABORTED);
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);
  }

  @Test
  void leBudgetTotalParÉvénementEstPartagéEntreLesRègles() {
    long[] eventBudget = {SafeRegex.EVENT_BUDGET};
    String text = "a".repeat(4096) + "!";
    int aborted = 0;
    for (int i = 0; i < 20; i++) {
      var rule = rule();
      rule.setMessageRegex(".*a.*b.*c" + i); // motif distinct, non validé (déjà en base)
      if (matcher.evaluate(rule, syslog(text, EventSeverity.INFO), eventBudget).aborted()) {
        aborted++;
      }
    }
    assertThat(aborted).isEqualTo(20);
    assertThat(eventBudget[0]).isLessThanOrEqualTo(0);
    // Budget épuisé : la règle suivante est interrompue sans rien lire.
    var cheap = rule();
    cheap.setMessageRegex("zzz");
    assertThat(matcher.evaluate(cheap, syslog(text, EventSeverity.INFO), eventBudget).aborted())
        .isTrue();
  }

  @Test
  void lAvertissementDUneRègleInterrompueEstÉmisAuPlusUneFoisParMinute() {
    var throttle = new WarningThrottle(60_000);
    UUID rule = UUID.randomUUID();
    assertThat(throttle.allow(rule, 0)).isTrue();
    assertThat(throttle.allow(rule, 30_000)).isFalse();
    assertThat(throttle.allow(UUID.randomUUID(), 30_000)).isTrue();
    assertThat(throttle.allow(rule, 60_000)).isTrue();
    assertThat(WarningThrottle.loggable("règle\nfausse ligne\u001b[31m" + "x".repeat(300)))
        .doesNotContain("\n")
        .doesNotContain("\u001b")
        .hasSizeLessThanOrEqualTo(120);
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
