package sn.ept.transdic1.overview.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Score de santé : chiffre attendu, niveau, explications et moyenne par site. */
class HealthScoreTests {
  @Test
  void unEquipementSansRienASignalerEstACentEtLeDitEnClair() {
    var health = HealthScore.of("UP", 0, 0, 0, false, 0.0);

    assertThat(health.score()).isEqualTo(100);
    assertThat(health.level()).isEqualTo("bon");
    assertThat(health.reasons()).containsExactly("Rien à signaler");
  }

  @Test
  void unEquipementHorsServiceEstAZeroSansAutreCalcul() {
    var health = HealthScore.of("DOWN", 0, 3, 0, true, 0.9);

    assertThat(health.score()).isZero();
    assertThat(health.level()).isEqualTo("critique");
    assertThat(health.reasons()).containsExactly("Hors service");
  }

  @Test
  void unEquipementEnMaintenanceNEstPasJuge() {
    var health = HealthScore.of("MAINTENANCE", 2, 0, 0, false, null);

    assertThat(health.score()).isNull();
    assertThat(health.level()).isEqualTo("maintenance");
  }

  @Test
  void chaqueCausePerdDesPointsEtSExplique() {
    var health = HealthScore.of("DEGRADED", 1, 2, 1, true, 0.5);

    // 25 (critique) + 15 (dégradé) + 20 (muet) + 16 (2 avertissements) + 20 (anomalies) + 2 (info)
    assertThat(health.score()).isEqualTo(2);
    assertThat(health.level()).isEqualTo("critique");
    assertThat(health.reasons())
        .containsExactly(
            "1 alerte critique (−25)",
            "Fonctionnement dégradé (−15)",
            "Aucune mesure depuis dix minutes (−20)",
            "2 avertissements (−16)",
            "Indicateurs hors de leur normale : 50 % (−20)",
            "1 information (−2)");
  }

  @Test
  void lesPenalitesSontPlafonneesParSeverite() {
    // Dix alertes d'information ne valent pas une panne : le plafond les retient à 10 points.
    var noisy = HealthScore.of("UP", 0, 0, 10, false, null);
    assertThat(noisy.score()).isEqualTo(90);
    assertThat(noisy.reasons()).containsExactly("10 informations (−10)");

    // Même chose pour les critiques : le score reste parlant au lieu de s'effondrer à zéro.
    var severe = HealthScore.of("UP", 9, 0, 0, false, null);
    assertThat(severe.score()).isEqualTo(40);
    assertThat(severe.reasons()).containsExactly("9 alertes critiques (−60)");
  }

  @Test
  void leTauxDAnomalieInconnuOuNulNeCoutePasDePoints() {
    assertThat(HealthScore.of("UP", 0, 0, 0, false, null).score()).isEqualTo(100);
    // Un seul indicateur sur cent hors normale : moins d'un demi-point, donc rien.
    assertThat(HealthScore.of("UP", 0, 0, 0, false, 0.01).score()).isEqualTo(100);
    assertThat(HealthScore.of("UP", 0, 0, 0, false, 1.0).score()).isEqualTo(60);
  }

  @Test
  void lesNiveauxSuiventLeScore() {
    assertThat(HealthScore.level(100)).isEqualTo("bon");
    assertThat(HealthScore.level(85)).isEqualTo("bon");
    assertThat(HealthScore.level(84)).isEqualTo("surveiller");
    assertThat(HealthScore.level(60)).isEqualTo("surveiller");
    assertThat(HealthScore.level(59)).isEqualTo("degrade");
    assertThat(HealthScore.level(30)).isEqualTo("degrade");
    assertThat(HealthScore.level(29)).isEqualTo("critique");
  }

  @Test
  void leSiteEstLaMoyenneDeSesEquipementsJuges() {
    // Les équipements en maintenance (null) ne comptent ni en bien ni en mal.
    var site = HealthScore.ofSite(Arrays.asList(100, 40, null, 70));

    assertThat(site.score()).isEqualTo(70);
    assertThat(site.level()).isEqualTo("surveiller");
    assertThat(site.reasons()).containsExactly("1 équipement en souffrance");
  }

  @Test
  void unSiteSansEquipementJugeNaPasDeScore() {
    assertThat(HealthScore.ofSite(List.of()).score()).isNull();
    assertThat(HealthScore.ofSite(Arrays.asList((Integer) null)).score()).isNull();
    assertThat(HealthScore.ofSite(List.of(90, 95)).reasons())
        .containsExactly("Tous les équipements sont au vert");
  }
}
