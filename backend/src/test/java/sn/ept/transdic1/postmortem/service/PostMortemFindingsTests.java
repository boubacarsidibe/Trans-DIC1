package sn.ept.transdic1.postmortem.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.Finding;

/** Constats du post-mortem : chaque règle sur un cas écrit à la main. */
class PostMortemFindingsTests {

  private static PostMortemFindings.Facts facts(
      boolean acknowledged, Double toAck, boolean resolved, Double toResolve, boolean auto) {
    return new PostMortemFindings.Facts(
        acknowledged,
        toAck,
        15,
        resolved,
        toResolve,
        auto,
        1,
        1,
        1,
        0,
        Set.of(),
        false,
        false,
        false,
        "Routeur",
        0,
        30,
        false,
        0,
        0,
        List.of(),
        List.of(),
        false);
  }

  private static List<String> texts(List<Finding> findings) {
    return findings.stream().map(f -> f.level() + " " + f.text()).toList();
  }

  @Test
  @DisplayName("Une prise en charge dans l'objectif est un bon point, au-delà une action")
  void acknowledgementAgainstTheTarget() {
    assertThat(texts(PostMortemFindings.evaluate(facts(true, 6.0, true, 30.0, false))))
        .contains("POSITIVE Prise en charge en 6 min, dans l'objectif de 15 min.");
    var late = PostMortemFindings.evaluate(facts(true, 23.0, true, 60.0, false));
    assertThat(texts(late))
        .contains("ACTION Prise en charge en 23 min, au-delà de l'objectif de 15 min.");
    assertThat(
            late.stream()
                .filter(f -> "ACTION".equals(f.level()))
                .findFirst()
                .orElseThrow()
                .recommendation())
        .contains("délai d'escalade");
  }

  @Test
  @DisplayName(
      "Un retour à la normale très court sans intervention fait soupçonner une règle bavarde")
  void shortAutomaticResolutionIsSuspicious() {
    assertThat(texts(PostMortemFindings.evaluate(facts(false, null, true, 2.0, true))))
        .anyMatch(t -> t.startsWith("WARNING Retour à la normale automatique en 2 min"));
    assertThat(texts(PostMortemFindings.evaluate(facts(false, null, true, 45.0, true))))
        .anyMatch(t -> t.startsWith("POSITIVE Retour à la normale constaté automatiquement"));
  }

  @Test
  @DisplayName("Regroupement, échecs d'envoi, récidive, page publique et astreinte injoignable")
  void organisationalFindings() {
    var f =
        new PostMortemFindings.Facts(
            true,
            10.0,
            15,
            true,
            50.0,
            false,
            4,
            1,
            3,
            2,
            Set.of("Teams"),
            true,
            true,
            true,
            "Routeur de coeur",
            2,
            30,
            false,
            1,
            0,
            List.of("redemarrer-nginx sur Web"),
            List.of(),
            false);
    List<String> all = texts(PostMortemFindings.evaluate(f));
    assertThat(all)
        .contains(
            "POSITIVE 4 alertes regroupées en un seul incident : 3 notifications évitées.",
            "ACTION 2 notifications en échec (Teams).",
            "ACTION 3e incident sur Routeur de coeur en 30 jours.",
            "ACTION 1 composant de page de statut publique touché sans communication aux usagers.",
            "POSITIVE Action corrective réussie : redemarrer-nginx sur Web.",
            "WARNING Cause non confirmée.",
            "WARNING L'incident a été escaladé aux administrateurs.");
    assertThat(all).anyMatch(t -> t.startsWith("ACTION La personne d'astreinte n'a pas pu"));
  }

  @Test
  @DisplayName("Les durées s'écrivent comme un humain les dirait")
  void durationsAreReadable() {
    assertThat(PostMortemFindings.minutes(0.5)).isEqualTo("30 s");
    assertThat(PostMortemFindings.minutes(23.4)).isEqualTo("23 min");
    assertThat(PostMortemFindings.minutes(135)).isEqualTo("2 h 15");
  }
}
