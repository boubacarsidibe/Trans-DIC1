package sn.ept.transdic1.impact.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Analyse d'impact : second chemin, niveau, synthèse, sur des topologies écrites à la main. */
class ImpactAssessmentTests {
  private final UUID core = UUID.randomUUID();
  private final UUID backup = UUID.randomUUID();
  private final UUID distribution = UUID.randomUUID();
  private final UUID access = UUID.randomUUID();
  private final UUID server = UUID.randomUUID();

  @Test
  @DisplayName("Un équipement relié aussi à un amont non touché reste joignable, et son aval aussi")
  void secondPathPropagates() {
    // core -> distribution -> access -> server, et distribution aussi reliée au routeur de secours.
    Set<UUID> touched = Set.of(core, distribution, access, server);
    Map<UUID, List<UUID>> upstreams =
        Map.of(
            distribution, List.of(core, backup),
            access, List.of(distribution),
            server, List.of(access));
    assertThat(ImpactAssessment.redundant(touched, core, upstreams))
        .containsExactlyInAnyOrder(distribution, access, server);
  }

  @Test
  @DisplayName("Sans second chemin, tout l'aval est perdu")
  void noSecondPath() {
    Set<UUID> touched = Set.of(core, distribution, server);
    Map<UUID, List<UUID>> upstreams =
        Map.of(distribution, List.of(core), server, List.of(distribution));
    assertThat(ImpactAssessment.redundant(touched, core, upstreams)).isEmpty();
  }

  @Test
  @DisplayName("Une boucle saisie par erreur ne crée pas de chemin imaginaire")
  void loopIsNotAPath() {
    Set<UUID> touched = Set.of(core, distribution, access);
    Map<UUID, List<UUID>> upstreams =
        Map.of(distribution, List.of(core, access), access, List.of(distribution));
    assertThat(ImpactAssessment.redundant(touched, core, upstreams)).isEmpty();
  }

  @Test
  @DisplayName("Le niveau monte avec l'aval perdu, et un composant public le rend critique")
  void levels() {
    assertThat(ImpactAssessment.level(0, 0, 0, 0)).isEqualTo("FAIBLE");
    assertThat(ImpactAssessment.level(1, 0, 0, 0)).isEqualTo("MOYEN");
    assertThat(ImpactAssessment.level(0, 2, 0, 0)).isEqualTo("MOYEN");
    assertThat(ImpactAssessment.level(3, 0, 0, 0)).isEqualTo("ELEVE");
    assertThat(ImpactAssessment.level(1, 1, 1, 0)).isEqualTo("ELEVE");
    assertThat(ImpactAssessment.level(1, 1, 1, 1)).isEqualTo("CRITIQUE");
    assertThat(ImpactAssessment.level(10, 0, 0, 0)).isEqualTo("CRITIQUE");
  }

  @Test
  @DisplayName("La synthèse se lit comme une phrase")
  void summary() {
    assertThat(ImpactAssessment.summary("Routeur de coeur", 4, 1, 3, 2, 1))
        .isEqualTo(
            "Arrêter Routeur de coeur rend injoignables 4 équipements en aval, interrompt 3"
                + " services surveillés et touche 2 composants de pages de statut, dont 1 visible"
                + " des usagers. 1 autre équipement reste joignable par un second chemin.");
    assertThat(ImpactAssessment.summary("Borne Wi-Fi", 0, 0, 0, 0, 0))
        .isEqualTo("Arrêter Borne Wi-Fi ne touche aucun autre équipement connu de la topologie.");
  }

  @Test
  @DisplayName("Les recommandations suivent les faits")
  void recommendations() {
    var advice =
        ImpactAssessment.recommendations(
            3, List.of("Messagerie"), List.of("Onduleur baie A"), "awa", false, 2);
    assertThat(advice)
        .anyMatch(a -> a.contains("3 équipements en aval"))
        .anyMatch(a -> a.contains("page de statut") && a.contains("Messagerie"))
        .anyMatch(a -> a.contains("Onduleur baie A"))
        .anyMatch(a -> a.contains("awa"))
        .anyMatch(a -> a.startsWith("2 alertes sont déjà ouvertes"));
  }
}
