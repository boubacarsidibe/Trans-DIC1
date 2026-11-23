package sn.ept.transdic1.threat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.threat.domain.SecurityEventKind;
import sn.ept.transdic1.threat.service.MitreAttack.Technique;
import sn.ept.transdic1.threat.service.WebAttackSignatures.Family;

/**
 * Repère MITRE ATT&CK (ADR 0048) : ce qui reçoit une technique, et surtout ce qui n'en reçoit pas.
 */
class MitreAttackTests {

  @Test
  @DisplayName("Chaque famille d'attaque reconnue par signature a une technique")
  void everyRecognisedFamilyHasATechnique() {
    for (Family family : Family.values()) {
      assertThat(MitreAttack.forFamily(family)).as(family.name()).isNotEmpty();
    }
  }

  @Test
  @DisplayName("Ce qui compte le plus : un soupçon du modèle n'a aucune technique")
  void whatMattersMostAmodelSuspicionHasNoTechnique() {
    // En inventer une ferait passer une hypothèse pour un fait.
    assertThat(
            MitreAttack.forEvent(
                SecurityEventKind.WEB_SUSPECT, "Forme inhabituelle (modèle appris)"))
        .isEmpty();
    assertThat(MitreAttack.forAlert(SecurityAlerts.Subject.SUSPECT)).isEmpty();
    assertThat(MitreAttack.forAlert(SecurityAlerts.Subject.VOLUME)).isEmpty();
  }

  @Test
  @DisplayName("Une exécution de commande se lit comme une porte d'entrée et ce qu'elle ouvre")
  void aCommandExecutionIsBothTheDoorAndWhatItOpens() {
    var techniques =
        MitreAttack.forEvent(SecurityEventKind.WEB_ATTACK, Family.EXECUTION_COMMANDE.label());

    assertThat(techniques).extracting(Technique::id).containsExactly("T1190", "T1059");
  }

  @Test
  @DisplayName("Plusieurs familles d'une même requête ne répètent pas une technique")
  void severalFamiliesOfOneRequestDoNotRepeatATechnique() {
    var label = Family.INJECTION_SQL.label() + ", " + Family.XSS.label();

    assertThat(MitreAttack.forEvent(SecurityEventKind.WEB_ATTACK, label))
        .extracting(Technique::id)
        .containsExactly("T1190");
  }

  @Test
  @DisplayName("Un échec d'authentification relève de la force brute")
  void anAuthenticationFailureIsBruteForce() {
    assertThat(MitreAttack.forEvent(SecurityEventKind.AUTH_FAILURE, null))
        .extracting(Technique::id)
        .containsExactly("T1110");
  }

  @Test
  @DisplayName("Une connexion réussie n'est pas hostile par elle-même")
  void aSuccessfulLoginIsNotHostileByItself() {
    assertThat(MitreAttack.forEvent(SecurityEventKind.AUTH_SUCCESS, null)).isEmpty();
    // Seule l'alerte « adresse jamais vue » le rattache à des comptes valides détournés.
    assertThat(MitreAttack.forAlert(SecurityAlerts.Subject.LOGIN))
        .extracting(Technique::id)
        .containsExactly("T1078");
  }

  @Test
  @DisplayName("Une erreur web isolée n'est pas un balayage, leur rafale l'est")
  void anIsolatedWebErrorIsNotAscan() {
    assertThat(MitreAttack.forEvent(SecurityEventKind.WEB_ERROR, null)).isEmpty();
    assertThat(MitreAttack.forAlert(SecurityAlerts.Subject.SCAN))
        .extracting(Technique::id)
        .containsExactly("T1595.003");
  }

  @Test
  @DisplayName("Les alertes réseau ont leur technique")
  void networkAlertsHaveTheirTechnique() {
    assertThat(MitreAttack.forAlert(SecurityAlerts.Subject.NETWORK_SCAN))
        .extracting(Technique::id)
        .containsExactly("T1046");
    assertThat(MitreAttack.forAlert(SecurityAlerts.Subject.FLOOD))
        .extracting(Technique::id)
        .containsExactly("T1498");
  }

  @Test
  @DisplayName("La fiche d'une sous-technique est sous son parent")
  void aSubTechniquePageIsUnderItsParent() {
    assertThat(MitreAttack.WORDLIST_SCANNING.url())
        .isEqualTo("https://attack.mitre.org/techniques/T1595/003/");
    assertThat(MitreAttack.BRUTE_FORCE.url())
        .isEqualTo("https://attack.mitre.org/techniques/T1110/");
  }

  @Test
  @DisplayName("Un libellé inconnu ou vide ne donne rien, sans échouer")
  void anUnknownOrBlankLabelGivesNothingWithoutFailing() {
    assertThat(MitreAttack.forEvent(SecurityEventKind.WEB_ATTACK, null)).isEmpty();
    assertThat(MitreAttack.forEvent(SecurityEventKind.WEB_ATTACK, "  ")).isEmpty();
    assertThat(MitreAttack.forEvent(SecurityEventKind.WEB_ATTACK, "Famille d'une version future"))
        .isEmpty();
  }
}
