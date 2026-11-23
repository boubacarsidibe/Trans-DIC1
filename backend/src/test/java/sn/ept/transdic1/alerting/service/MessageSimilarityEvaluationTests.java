package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Épreuve chiffrée de la ressemblance des messages (M10, ADR 0039), dans l'esprit de {@code
 * AnomalyDetectorEvaluationTests} : avant de choisir un seuil, on mesure ce que la mesure sépare.
 *
 * <p><b>Le résultat est négatif, et c'est pour cela que ce test existe.</b> Sur les messages que la
 * plateforme écrit vraiment, la part de mots communs ne sépare pas « même fait » de « faits
 * différents » : les deux populations se chevauchent exactement à 0,5. « Débit entrant saturé » et
 * « Débit sortant saturé » partagent autant de vocabulaire que « Débit entrant saturé » et « Débit
 * entrant inhabituel ». Aucun seuil ne peut donc attraper le second couple sans attraper le
 * premier.
 *
 * <p>D'où le seuil retenu, 0,6 : au-dessus du chevauchement, là où ne passent plus que des messages
 * de gabarit quasi identique. La règle ne sert alors qu'à un cas, mais un cas fréquent et utile —
 * <i>la même alerte sur plusieurs machines d'un site</i>. Les couples « même fait, autres mots »
 * sont laissés à la topologie, qui dit par quoi deux alertes sont liées au lieu de le deviner.
 */
class MessageSimilarityEvaluationTests {

  /** Un couple de messages, avec les noms d'équipements à retirer. */
  private record Pair(String first, String second, String firstName, String secondName) {

    double score() {
      return MessageSimilarity.between(first, second, firstName, secondName);
    }
  }

  /** Couples qui décrivent le même fait sur deux machines ou par deux détecteurs. */
  private static final List<Pair> SAME_FACT =
      List.of(
          new Pair(
              "Processeur à 92,4 % sur Routeur principal (seuil 90 %)",
              "Processeur à 87,1 % sur Commutateur Bloc B (seuil 90 %)",
              "Routeur principal",
              "Commutateur Bloc B"),
          new Pair(
              "Perte de collecte sur Routeur principal",
              "Perte de collecte sur Commutateur Bloc B",
              "Routeur principal",
              "Commutateur Bloc B"),
          new Pair(
              "Disque /var plein à 95 % sur Serveur A",
              "Disque /var plein à 97 % sur Serveur B", "Serveur A", "Serveur B"),
          new Pair(
              "Débit entrant au-dessus de 90 % sur Commutateur A",
              "Débit entrant inhabituel sur Commutateur B : 94 %",
              "Commutateur A",
              "Commutateur B"),
          new Pair(
              "Mémoire à 95 % sur Serveur A",
              "Mémoire inhabituelle sur Serveur B : 96 %", "Serveur A", "Serveur B"));

  /** Couples qui ne décrivent pas le même fait, même s'ils arrivent en même temps. */
  private static final List<Pair> DIFFERENT_FACT =
      List.of(
          new Pair(
              "Processeur à 92,4 % sur Routeur principal",
              "Disque /var plein à 95 % sur Serveur applicatif",
              "Routeur principal",
              "Serveur applicatif"),
          new Pair(
              "Événement réseau « Interface coupée (linkDown) » sur Commutateur A",
              "Plus de données reçues de Serveur B depuis 30 minutes",
              "Commutateur A",
              "Serveur B"),
          new Pair(
              "Débit entrant au-dessus de 90 % sur Commutateur A",
              "Débit sortant au-dessus de 90 % sur Commutateur B",
              "Commutateur A",
              "Commutateur B"),
          new Pair(
              "Mémoire à 95 % sur Serveur A",
              "Processeur à 95 % sur Serveur B", "Serveur A", "Serveur B"),
          new Pair(
              "Latence de la sonde HTTP inhabituelle sur Serveur A",
              "Débit entrant inhabituel sur Commutateur B",
              "Serveur A",
              "Commutateur B"));

  @Test
  @DisplayName("Les deux populations se chevauchent : aucun seuil ne les sépare")
  void thetwoPopulationsOverlapNothresholdSeparatesThem() {
    double worstSameFact = SAME_FACT.stream().mapToDouble(Pair::score).min().orElseThrow();
    double bestDifferentFact = DIFFERENT_FACT.stream().mapToDouble(Pair::score).max().orElseThrow();

    // « Débit entrant » contre « Débit sortant » obtient exactement le même score que « Débit
    // entrant saturé » contre « Débit entrant inhabituel ». Ce test échouera si quelqu'un croit
    // avoir trouvé un seuil qui sépare : il n'y en a pas, il faut d'autres indices.
    assertThat(worstSameFact).isEqualTo(0.5);
    assertThat(bestDifferentFact).isEqualTo(0.5);
    assertThat(worstSameFact).isLessThanOrEqualTo(bestDifferentFact);
  }

  @Test
  @DisplayName("À 0,6, aucun faux rapprochement — c'est ce qu'on achète en renonçant au reste")
  void atzeroPointSixNofalseGrouping() {
    assertThat(DIFFERENT_FACT.stream().filter(pair -> pair.score() >= 0.6)).isEmpty();
  }

  @Test
  @DisplayName("À 0,6, le cas utile passe : la même alerte sur plusieurs machines d'un site")
  void atzeroPointSixTheUsefulCasePasses() {
    long caught = SAME_FACT.stream().filter(pair -> pair.score() >= 0.6).count();

    // Trois couples sur cinq : ceux de gabarit identique, qui sont aussi les plus fréquents —
    // une règle de seuil ou un détecteur qui parle de plusieurs machines à la fois.
    assertThat(caught).isEqualTo(3);
  }

  @Test
  @DisplayName("Descendre à 0,5 attraperait les cinq couples utiles, et un faux avec")
  void droppingToZeroPointFiveWouldCatchAllFiveAndOnefalseOne() {
    long caught = SAME_FACT.stream().filter(pair -> pair.score() >= 0.5).count();
    long wrong = DIFFERENT_FACT.stream().filter(pair -> pair.score() >= 0.5).count();

    // Le marché est là : deux couples utiles de plus contre un rapprochement faux — « débit
    // entrant » collé à « débit sortant ». Un faux regroupement coûte plus cher qu'une
    // notification de trop : il fait croire à l'opérateur que deux faits n'en sont qu'un, et le
    // détourne d'une piste. C'est pourquoi le seuil reste à 0,6.
    assertThat(caught).isEqualTo(5);
    assertThat(wrong).isEqualTo(1);
  }
}
