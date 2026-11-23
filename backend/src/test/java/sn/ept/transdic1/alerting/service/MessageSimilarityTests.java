package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ressemblance entre messages d'alerte (M10) : ce qu'elle rapproche, et surtout ce qu'elle ne
 * rapproche pas. Les messages sont ceux que la plateforme écrit vraiment, recopiés de ses gabarits.
 */
class MessageSimilarityTests {

  @Test
  @DisplayName("Deux alertes du même gabarit se ressemblent, malgré des chiffres différents")
  void twoalertsFromTheSameTemplateLookAlike() {
    double similarity =
        MessageSimilarity.between(
            "Processeur à 92,4 % sur Routeur principal (seuil 90 %)",
            "Processeur à 87,1 % sur Commutateur Bloc B (seuil 90 %)",
            "Routeur principal",
            "Commutateur Bloc B");

    // Les chiffres sont retirés et les noms propres exclus : il ne reste que le fait décrit.
    assertThat(similarity).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Deux faits différents ne se ressemblent pas")
  void twodifferentFactsDoNotLookAlike() {
    double similarity =
        MessageSimilarity.between(
            "Processeur à 92,4 % sur Routeur principal",
            "Disque /var plein à 95 % sur Serveur applicatif",
            "Routeur principal",
            "Serveur applicatif");

    assertThat(similarity).isLessThan(0.2);
  }

  @Test
  @DisplayName("Ce que la mesure apporte : la même alerte sur plusieurs machines d'un site")
  void whatTheMeasureAddsTheSameAlertOnSeveralMachinesOfaSite() {
    // Une règle de seuil qui parle de cinq commutateurs, ou un détecteur qui ouvre la même alerte
    // sur plusieurs machines : les messages sont de gabarit identique, la ressemblance vaut 1, et
    // c'est le cas que la règle « même site » sert vraiment. Le schéma ne porte pas la clé
    // d'indicateur sur l'alerte, donc il n'y a pas de signal plus direct.
    double similarity =
        MessageSimilarity.between(
            "Débit entrant au-dessus de 90 % sur Commutateur A",
            "Débit entrant au-dessus de 90 % sur Commutateur B", "Commutateur A", "Commutateur B");

    assertThat(similarity).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Sa vraie limite : deux formulations proches du même fait restent sous le seuil")
  void itsrealLimitTwonearFormulationsStayBelowTheThreshold() {
    // Un seuil et une anomalie sur le même indicateur décrivent le même fait, mais ne partagent
    // que la moitié de leur vocabulaire — autant que « débit entrant » et « débit sortant », qui
    // ne sont pas le même fait. Aucun seuil ne sépare les deux : voir
    // MessageSimilarityEvaluationTests, qui le mesure.
    double similarity =
        MessageSimilarity.between(
            "Débit entrant au-dessus de 90 % sur Commutateur A",
            "Débit entrant inhabituel sur Commutateur B : 94 %", "Commutateur A", "Commutateur B");

    assertThat(similarity).isEqualTo(0.5);
  }

  @Test
  @DisplayName("Sa limite : le même fait dit avec d'autres mots reste étranger")
  void itslimitTheSameFactInOtherWordsStaysaStranger() {
    // « Interface coupée » et « plus aucune mesure » décrivent souvent la même panne. Aucune
    // mesure de vocabulaire ne les rapprochera : c'est la topologie qui le fait, pas celle-ci.
    double similarity =
        MessageSimilarity.between(
            "Événement réseau « Interface coupée (linkDown) » sur Commutateur A",
            "Plus de données reçues de Serveur B depuis 30 minutes",
            "Commutateur A",
            "Serveur B");

    assertThat(similarity).isLessThan(0.2);
  }

  @Test
  @DisplayName("Les noms propres sont retirés, pas seulement ignorés par chance")
  void propernamesAreRemovedNotIgnoredByLuck() {
    String first = "Perte de collecte sur Routeur principal";
    String second = "Perte de collecte sur Commutateur Bloc B";

    // Sans retirer les noms, « routeur » et « commutateur » feraient baisser la ressemblance de
    // deux alertes pourtant identiques.
    assertThat(MessageSimilarity.between(first, second)).isLessThan(1.0);
    assertThat(MessageSimilarity.between(first, second, "Routeur principal", "Commutateur Bloc B"))
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("Un message vide ne ressemble à rien, plutôt que de ressembler à tout")
  void anemptyMessageLooksLikeNothingRatherThanEverything() {
    assertThat(MessageSimilarity.between(null, "Processeur chargé")).isZero();
    assertThat(MessageSimilarity.between("", "Processeur chargé")).isZero();
    assertThat(MessageSimilarity.between("92 %", "87 %")).isZero();
    assertThat(MessageSimilarity.between(null, null)).isZero();
  }

  @Test
  @DisplayName("Accents, casse et ponctuation ne changent rien")
  void accentsCaseAndPunctuationChangeNothing() {
    assertThat(MessageSimilarity.between("Mémoire saturée !", "MEMOIRE SATUREE")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Les mots de liaison de nos gabarits ne gonflent pas la ressemblance")
  void thefillerWordsOfOurTemplatesDoNotInflateSimilarity() {
    // « alerte », « pour », « depuis », « équipement » reviennent partout : les garder ferait
    // passer deux faits étrangers pour proches.
    double similarity =
        MessageSimilarity.between(
            "Alerte pour cet équipement depuis cette mesure : disque plein",
            "Alerte pour cet équipement depuis cette mesure : interface coupée");

    assertThat(similarity).isZero();
  }
}
