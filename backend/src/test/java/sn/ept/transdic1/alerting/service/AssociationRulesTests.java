package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.service.AssociationRules.Observation;

/**
 * Règles d'association sur les alertes (M9) : ce qui fait une paire, ce qui n'en fait pas, et ce
 * qu'on refuse de proposer.
 */
class AssociationRulesTests {
  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

  private static Observation seen(long window, UUID equipment) {
    return new Observation(window, equipment);
  }

  @Test
  @DisplayName("Deux équipements qui tombent toujours ensemble sont proposés")
  void twoequipmentsAlwaysFallingTogetherAreProposed() {
    var observations = new ArrayList<Observation>();
    for (long window = 0; window < 6; window++) {
      observations.add(seen(window, A));
      observations.add(seen(window, B));
    }

    var pairs = AssociationRules.mine(observations, 4, 0.6, Set.of());

    assertThat(pairs).hasSize(1);
    var pair = pairs.get(0);
    assertThat(pair.first()).isEqualTo(A);
    assertThat(pair.second()).isEqualTo(B);
    assertThat(pair.together()).isEqualTo(6);
    assertThat(pair.confidence()).isEqualTo(1.0);
    assertThat(pair.support()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("La confiance retient la direction la plus forte, pas la moyenne")
  void theconfidenceKeepsTheStrongerDirection() {
    var observations = new ArrayList<Observation>();
    // L'onduleur (A) tombe cinq fois, et la baie (B) chaque fois avec lui. Mais la baie tombe
    // aussi quinze fois toute seule.
    for (long window = 0; window < 5; window++) {
      observations.add(seen(window, A));
      observations.add(seen(window, B));
    }
    for (long window = 5; window < 20; window++) {
      observations.add(seen(window, B));
    }

    var pairs = AssociationRules.mine(observations, 4, 0.6, Set.of());

    assertThat(pairs).hasSize(1);
    // A → B vaut 1 (A n'est jamais seul), B → A vaut 0,25. La moyenne aurait caché un lien réel.
    assertThat(pairs.get(0).confidence()).isEqualTo(1.0);
    assertThat(pairs.get(0).support()).isCloseTo(0.25, Offset.offset(0.001));
  }

  @Test
  @DisplayName("Une coïncidence unique n'est pas une règle")
  void asingleCoincidenceIsNotArule() {
    var observations = new ArrayList<Observation>();
    for (long window = 0; window < 20; window++) {
      observations.add(seen(window, A));
    }
    for (long window = 100; window < 120; window++) {
      observations.add(seen(window, B));
    }
    // Une seule fenêtre commune, par hasard.
    observations.add(seen(5, B));

    assertThat(AssociationRules.mine(observations, 4, 0.6, Set.of())).isEmpty();
  }

  @Test
  @DisplayName("Deux machines bavardes mais indépendantes ne sont pas liées")
  void twotalkativeButIndependentMachinesAreNotLinked() {
    var observations = new ArrayList<Observation>();
    // A tombe toutes les fenêtres paires, B toutes les impaires : jamais ensemble.
    for (long window = 0; window < 40; window++) {
      observations.add(seen(window, window % 2 == 0 ? A : B));
    }

    assertThat(AssociationRules.mine(observations, 4, 0.6, Set.of())).isEmpty();
  }

  @Test
  @DisplayName("Ce que le seuil de confiance écarte : beaucoup d'alertes, peu de lien")
  void whattheConfidenceThresholdDiscards() {
    var observations = new ArrayList<Observation>();
    // Cinq fenêtres communes, mais chacun apparaît dans cinquante : la confiance vaut 0,1.
    for (long window = 0; window < 50; window++) {
      observations.add(seen(window, A));
    }
    for (long window = 45; window < 95; window++) {
      observations.add(seen(window, B));
    }

    var loose = AssociationRules.mine(observations, 4, 0.05, Set.of());
    var strict = AssociationRules.mine(observations, 4, 0.6, Set.of());

    assertThat(loose).hasSize(1);
    assertThat(loose.get(0).confidence()).isCloseTo(0.1, Offset.offset(0.001));
    assertThat(strict).isEmpty();
  }

  @Test
  @DisplayName("Plusieurs alertes d'un équipement dans une fenêtre ne comptent qu'une fois")
  void severalAlertsOfOneequipmentInAwindowCountOnce() {
    var observations = new ArrayList<Observation>();
    for (long window = 0; window < 5; window++) {
      observations.add(seen(window, A));
      observations.add(seen(window, A));
      observations.add(seen(window, A));
      observations.add(seen(window, B));
    }

    var pairs = AssociationRules.mine(observations, 4, 0.6, Set.of());

    // Sans cela, une machine bavarde ferait grimper tous les comptes qui la touchent.
    assertThat(pairs.get(0).firstWindows()).isEqualTo(5);
    assertThat(pairs.get(0).together()).isEqualTo(5);
  }

  @Test
  @DisplayName("Ce que la topologie sait déjà n'est pas proposé")
  void whatThetopologyAlreadyKnowsIsNotProposed() {
    var observations = new ArrayList<Observation>();
    for (long window = 0; window < 6; window++) {
      observations.add(seen(window, A));
      observations.add(seen(window, B));
      observations.add(seen(window, C));
    }

    var pairs =
        AssociationRules.mine(observations, 4, 0.6, Set.of(AssociationRules.canonical(A, B)));

    // Le but est de compléter la topologie, pas de la répéter.
    assertThat(pairs)
        .extracting(p -> List.of(p.first(), p.second()))
        .containsExactlyInAnyOrder(List.of(A, C), List.of(B, C));
  }

  @Test
  @DisplayName("Trois équipements liés donnent les trois paires")
  void threeLinkedequipmentsGiveThethreePairs() {
    var observations = new ArrayList<Observation>();
    for (long window = 0; window < 5; window++) {
      observations.add(seen(window, A));
      observations.add(seen(window, B));
      observations.add(seen(window, C));
    }

    assertThat(AssociationRules.mine(observations, 4, 0.6, Set.of())).hasSize(3);
  }

  @Test
  @DisplayName("Les paires sont rendues de la plus confiante à la moins confiante")
  void thepairsAreReturnedFromMostToLeastConfident() {
    var observations = new ArrayList<Observation>();
    for (long window = 0; window < 10; window++) {
      observations.add(seen(window, A));
      observations.add(seen(window, B));
      if (window < 6) {
        observations.add(seen(window, C));
      }
    }

    var pairs = AssociationRules.mine(observations, 4, 0.5, Set.of());

    assertThat(pairs).hasSize(3);
    assertThat(pairs.get(0).confidence()).isEqualTo(1.0);
    assertThat(pairs)
        .extracting(AssociationRules.Pair::confidence)
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  @Test
  @DisplayName("L'ordre de la paire ne dépend pas de l'ordre des alertes")
  void thepairOrderDoesNotDependOnTheAlertOrder() {
    assertThat(AssociationRules.canonical(A, B)).containsExactly(A, B);
    assertThat(AssociationRules.canonical(B, A)).containsExactly(A, B);
  }

  @Test
  @DisplayName("L'ordre est celui de PostgreSQL, pas celui de UUID.compareTo")
  void theorderIsPostgresNotUuidCompareTo() {
    // UUID.compareTo compare deux long signés : celui qui commence par ff y passe avant celui qui
    // commence par 00. PostgreSQL compare octet par octet et dit le contraire, et la table porte
    // une contrainte sur cet ordre — c'est elle qui a révélé la différence.
    UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
    UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");

    assertThat(high.compareTo(low)).isNegative();
    assertThat(AssociationRules.canonical(high, low)).containsExactly(low, high);
    assertThat(AssociationRules.canonical(low, high)).containsExactly(low, high);
  }

  @Test
  @DisplayName("Les fenêtres sont calculées de la même façon pour tout le monde")
  void thewindowsAreComputedTheSameWayForEveryone() {
    long minutes = 10 * 60_000L;
    Instant base = Instant.parse("2026-11-23T10:00:00Z");

    assertThat(AssociationRules.window(base, minutes))
        .isEqualTo(AssociationRules.window(base.plusSeconds(599), minutes));
    assertThat(AssociationRules.window(base, minutes))
        .isNotEqualTo(AssociationRules.window(base.plusSeconds(600), minutes));
    assertThatThrownBy(() -> AssociationRules.window(base, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Sans alerte, rien n'est proposé")
  void withoutAlertsNothingIsProposed() {
    assertThat(AssociationRules.mine(List.of(), 4, 0.6, Set.of())).isEmpty();
    assertThat(AssociationRules.mine(null, 4, 0.6, Set.of())).isEmpty();
  }
}
