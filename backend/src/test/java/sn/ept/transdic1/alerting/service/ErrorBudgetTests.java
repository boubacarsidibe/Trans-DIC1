package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Budget d'erreur d'un service (M8) : ce qu'il autorise, ce qu'il reste, et à quelle vitesse. */
class ErrorBudgetTests {
  private static final Duration MONTH = Duration.ofDays(30);
  private static final double TARGET = 0.995;

  private static ErrorBudget.Status status(long samples, long failed) {
    return new ErrorBudget.Status(TARGET, samples, failed, MONTH);
  }

  @Test
  @DisplayName("Un objectif de 99,5 % sur trente jours autorise trois heures et demie")
  void atargetOfNinetyNinePointFiveAllowsThreeAndAhalfHours() {
    assertThat(ErrorBudget.budgetMinutes(TARGET, MONTH)).isEqualTo(216);
    assertThat(ErrorBudget.budgetMinutes(0.999, MONTH)).isEqualTo(43);
    assertThat(ErrorBudget.budgetMinutes(0.99, MONTH)).isEqualTo(432);
  }

  @Test
  @DisplayName("Un service parfait n'a rien dépensé")
  void aperfectServiceHasSpentNothing() {
    var status = status(43_200, 0);

    assertThat(status.availability()).isEqualTo(1.0);
    assertThat(status.consumed()).isZero();
    assertThat(status.remainingMinutes()).isEqualTo(216);
    assertThat(status.exhausted()).isFalse();
  }

  @Test
  @DisplayName("Ce que le budget apporte : « 160 % dépensés » parle, « 99,2 % » non")
  void whatTheBudgetAddsAspentPercentageSpeaks() {
    // 99,2 % de disponibilité : le chiffre paraît bon, et pourtant l'objectif est manqué.
    var status = status(10_000, 80);

    assertThat(status.availability()).isCloseTo(0.992, Offset.offset(0.0001));
    assertThat(status.consumed()).isCloseTo(1.6, Offset.offset(0.01));
    assertThat(status.exhausted()).isTrue();
    assertThat(status.remainingMinutes()).isZero();
  }

  @Test
  @DisplayName("Un budget à moitié dépensé laisse la moitié du temps")
  void ahalfSpentBudgetLeavesHalfTheTime() {
    var status = status(10_000, 25);

    assertThat(status.consumed()).isCloseTo(0.5, Offset.offset(0.01));
    assertThat(status.remainingMinutes()).isEqualTo(108);
  }

  @Test
  @DisplayName("Sans assez de relevés, on ne conclut rien")
  void withoutEnoughSamplesNoconclusion() {
    assertThat(status(10, 5).measurable()).isFalse();
    assertThat(status(60, 0).measurable()).isTrue();
    // Aucun relevé : on ne reproche rien au silence, c'est l'affaire de l'alerte « plus de données
    // ».
    assertThat(status(0, 0).availability()).isEqualTo(1.0);
    assertThat(status(0, 0).consumed()).isZero();
  }

  @Test
  @DisplayName("Un objectif de 100 % n'a pas de budget, et le dit")
  void atargetOfOneHundredPercentHasNobudget() {
    var perfect = new ErrorBudget.Status(1.0, 1_000, 0, MONTH);
    var broken = new ErrorBudget.Status(1.0, 1_000, 1, MONTH);

    assertThat(perfect.consumed()).isZero();
    assertThat(broken.consumed()).isInfinite();
    assertThat(broken.exhausted()).isTrue();
    assertThat(broken.remainingMinutes()).isZero();
  }

  @Test
  @DisplayName("Le taux de consommation dit la vitesse, pas la quantité")
  void theburnRateSaysTheSpeedNotTheQuantity() {
    // 1 % d'échecs sur une heure, pour un budget de 0,5 % : deux fois trop vite.
    assertThat(ErrorBudget.burnRate(1_000, 10, TARGET, 60)).isCloseTo(2.0, Offset.offset(0.01));
    // La moitié des relevés en échec : cent fois trop vite, le mois est perdu en sept heures.
    assertThat(ErrorBudget.burnRate(1_000, 500, TARGET, 60)).isCloseTo(100.0, Offset.offset(0.1));
    // Aucun échec : rien ne part.
    assertThat(ErrorBudget.burnRate(1_000, 0, TARGET, 60)).isZero();
  }

  @Test
  @DisplayName("Un taux calculé sur deux pings ne vaut rien, et vaut donc zéro")
  void aburnRateComputedOnTwopingsIsWorthNothing() {
    // Sans ce garde-fou, un échec sur deux relevés donnerait un taux de cent et réveillerait
    // quelqu'un pour une coupure de deux minutes.
    assertThat(ErrorBudget.burnRate(2, 1, TARGET, 60)).isZero();
    assertThat(ErrorBudget.burnRate(59, 30, TARGET, 60)).isZero();
    assertThat(ErrorBudget.burnRate(60, 30, TARGET, 60)).isPositive();
  }

  @Test
  @DisplayName("Le seuil d'alerte rapide correspond à 2 % du budget en une heure")
  void thefastBurnThresholdMatchesTwopercentOfTheBudgetInanhour() {
    // 14,4 est le taux auquel une heure consomme 2 % d'un budget de trente jours : la fenêtre
    // d'objectif fait 720 heures, et 14,4 / 720 vaut bien 2 %.
    assertThat(ErrorBudget.FAST_BURN / MONTH.toHours()).isCloseTo(0.02, Offset.offset(0.0001));
  }

  @Test
  @DisplayName("Le taux est dit en mots lisibles")
  void therateIsSaidInReadableWords() {
    assertThat(ErrorBudget.describeBurn(2.5)).isEqualTo("2,5 fois");
    assertThat(ErrorBudget.describeBurn(14.4)).isEqualTo("14 fois");
    assertThat(ErrorBudget.describeBurn(250)).isEqualTo("plus de cent fois");
  }
}
