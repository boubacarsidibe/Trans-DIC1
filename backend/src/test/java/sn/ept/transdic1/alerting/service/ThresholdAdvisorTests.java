package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;

/**
 * Seuils proposés d'après l'historique (M14) : les centiles, leur arrondi, et le sens des
 * propositions selon l'opérateur.
 */
class ThresholdAdvisorTests {

  /** Série d'occupation disque : d'ordinaire autour de 60 %, avec quelques pointes. */
  private static List<BigDecimal> disk() {
    var values = new ArrayList<BigDecimal>();
    for (int i = 0; i < 950; i++) {
      values.add(BigDecimal.valueOf(55 + i % 10)); // 55 à 64
    }
    for (int i = 0; i < 40; i++) {
      values.add(BigDecimal.valueOf(88)); // pointes
    }
    for (int i = 0; i < 10; i++) {
      values.add(BigDecimal.valueOf(97)); // incidents
    }
    return values;
  }

  @Test
  @DisplayName("Les centiles décrivent ce que la machine fait d'habitude")
  void percentilesDescribeWhatTheMachineUsuallyDoes() {
    var distribution = ThresholdAdvisor.describe(disk());

    assertThat(distribution).isNotNull();
    assertThat(distribution.samples()).isEqualTo(1_000);
    assertThat(distribution.minimum()).isEqualByComparingTo("55");
    assertThat(distribution.median()).isEqualByComparingTo("60");
    // 95 % des mesures tiennent dans le régime ordinaire (55 à 64) : les pointes et les
    // incidents sont les 5 % du dessus, et c'est exactement ce qu'un seuil doit viser.
    assertThat(distribution.p95()).isEqualByComparingTo("64");
    assertThat(distribution.p99()).isEqualByComparingTo("88");
    assertThat(distribution.maximum()).isEqualByComparingTo("97");
  }

  @Test
  @DisplayName("Sans assez de mesures, on ne propose rien")
  void withoutEnoughSamplesNothingIsProposed() {
    assertThat(ThresholdAdvisor.describe(List.of(BigDecimal.ONE))).isNull();
    assertThat(ThresholdAdvisor.describe(List.of())).isNull();
    assertThat(ThresholdAdvisor.describe(null)).isNull();
    // Juste en dessous du minimum exigé.
    var tooFew = new ArrayList<BigDecimal>();
    for (int i = 0; i < ThresholdAdvisor.MIN_SAMPLES - 1; i++) {
      tooFew.add(BigDecimal.TEN);
    }
    assertThat(ThresholdAdvisor.describe(tooFew)).isNull();
  }

  @Test
  @DisplayName("Au-dessus d'un seuil, les propositions vont du prudent au sensible")
  void aboveAThresholdProposalsGoFromCautiousToSensitive() {
    var proposals =
        ThresholdAdvisor.propose(
            ComparisonOperator.GREATER_THAN, ThresholdAdvisor.describe(disk()), "%");

    assertThat(proposals).isNotEmpty();
    assertThat(proposals.get(0).label()).isEqualTo("Prudent");
    // Les seuils vont du plus haut au plus bas, sans doublon : deux centiles qui tombent au même
    // endroit après arrondi ne donnent qu'une proposition.
    for (int i = 1; i < proposals.size(); i++) {
      assertThat(proposals.get(i).threshold()).isLessThan(proposals.get(i - 1).threshold());
    }
    // Le seuil de retour est toujours du côté normal du seuil d'ouverture.
    for (var proposal : proposals) {
      if (proposal.recovery() != null) {
        assertThat(proposal.recovery()).isLessThanOrEqualTo(proposal.threshold());
      }
    }
  }

  @Test
  @DisplayName("En dessous d'un seuil, les propositions se lisent sur le bas de la distribution")
  void belowAThresholdProposalsAreReadFromTheBottom() {
    var proposals =
        ThresholdAdvisor.propose(
            ComparisonOperator.LESS_THAN, ThresholdAdvisor.describe(disk()), "%");

    assertThat(proposals).isNotEmpty();
    // Le prudent alerte au plus bas, le sensible au plus haut : l'inverse du cas précédent.
    for (int i = 1; i < proposals.size(); i++) {
      assertThat(proposals.get(i).threshold()).isGreaterThan(proposals.get(i - 1).threshold());
    }
    for (var proposal : proposals) {
      if (proposal.recovery() != null) {
        assertThat(proposal.recovery()).isGreaterThanOrEqualTo(proposal.threshold());
      }
    }
  }

  @Test
  @DisplayName("Une égalité n'a pas de seuil à proposer")
  void anequalityHasNoThresholdToPropose() {
    assertThat(
            ThresholdAdvisor.propose(
                ComparisonOperator.EQUAL, ThresholdAdvisor.describe(disk()), "%"))
        .isEmpty();
    assertThat(ThresholdAdvisor.propose(ComparisonOperator.GREATER_THAN, null, "%")).isEmpty();
  }

  @Test
  @DisplayName("Les seuils sont arrondis comme un humain les aurait écrits")
  void thresholdsAreRoundedTheWayAHumanWouldWriteThem() {
    // Pourcentages : au multiple de 5, et jamais au-dessus de 100.
    assertThat(ThresholdAdvisor.round(new BigDecimal("87.3"), "%")).isEqualByComparingTo("85");
    assertThat(ThresholdAdvisor.round(new BigDecimal("88.0"), "%")).isEqualByComparingTo("90");
    assertThat(ThresholdAdvisor.round(new BigDecimal("103"), "%")).isEqualByComparingTo("100");
    // Petites valeurs : un chiffre après la virgule (une latence de 2,4 ms).
    assertThat(ThresholdAdvisor.round(new BigDecimal("2.43"), "ms")).isEqualByComparingTo("2.4");
    // Valeurs moyennes : au multiple de 5, avec ou sans unite -- on cherche justement le
    // seuil, et l'unite est souvent encore vide a ce moment-la.
    assertThat(ThresholdAdvisor.round(new BigDecimal("142.7"), "ms")).isEqualByComparingTo("145");
    assertThat(ThresholdAdvisor.round(new BigDecimal("65.62"), null)).isEqualByComparingTo("65");
    // Grands nombres : deux chiffres significatifs.
    assertThat(ThresholdAdvisor.round(new BigDecimal("1234567"), "o"))
        .isEqualByComparingTo("1200000");
    assertThat(ThresholdAdvisor.round(null, "%")).isNull();
  }

  @Test
  @DisplayName("Un seuil de retour confondu avec l'ouverture n'est pas proposé")
  void arecoveryThresholdEqualToTheOpeningIsNotProposed() {
    // Série parfaitement plate : tous les centiles tombent au même endroit.
    var flat = new ArrayList<BigDecimal>();
    for (int i = 0; i < 500; i++) {
      flat.add(BigDecimal.valueOf(70));
    }

    var proposals =
        ThresholdAdvisor.propose(
            ComparisonOperator.GREATER_THAN, ThresholdAdvisor.describe(flat), "%");

    // Une seule proposition subsiste, et sans seuil de retour : tout est au même endroit.
    assertThat(proposals).hasSize(1);
    assertThat(proposals).allSatisfy(proposal -> assertThat(proposal.recovery()).isNull());
  }
}
