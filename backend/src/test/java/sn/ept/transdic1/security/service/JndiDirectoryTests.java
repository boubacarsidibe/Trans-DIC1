package sn.ept.transdic1.security.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ce qui se vérifie du dialogue avec l'annuaire sans annuaire (ADR 0046) : la construction du nom
 * distingué, l'échappement, et le refus d'envoyer un mot de passe en clair.
 *
 * <p>La liaison elle-même n'est pas éprouvée ici, et ne peut pas l'être : il n'y a pas d'annuaire
 * sur le poste de développement, et le projet n'ajoute pas de serveur LDAP embarqué pour les tests.
 * C'est écrit tel quel dans l'ADR — le code derrière le port reste mince pour cette raison.
 */
class JndiDirectoryTests {

  private static JndiDirectory directory(String url, boolean allowInsecure) {
    return new JndiDirectory(
        url,
        "uid={0},ou=people,dc=ept,dc=sn",
        "mail",
        "memberOf",
        "",
        "(member={0})",
        allowInsecure,
        5_000);
  }

  @Test
  @DisplayName("Sans URL ni gabarit, l'annuaire n'existe pas")
  void withoutAnurlOrApatternTheDirectoryDoesNotExist() {
    assertThat(directory("", false).enabled()).isFalse();
    assertThat(
            new JndiDirectory("ldaps://a", "", "mail", "memberOf", "", "(member={0})", false, 5_000)
                .enabled())
        .isFalse();
  }

  @Test
  @DisplayName("Ce qui compte le plus : le mot de passe n'est pas envoyé en clair")
  void whatMattersMostThepasswordIsNotSentInTheClear() {
    // Une liaison simple sur ldap:// envoie le mot de passe tel quel sur le réseau. Le service
    // refuse de le faire, et c'est un refus, pas un avertissement.
    assertThat(directory("ldap://annuaire.ept.sn", false).enabled()).isFalse();
    assertThat(directory("ldaps://annuaire.ept.sn", false).enabled()).isTrue();
    // L'exploitation peut passer outre, mais il faut l'écrire.
    assertThat(directory("ldap://annuaire.ept.sn", true).enabled()).isTrue();
  }

  @Test
  @DisplayName("Rien n'est tenté quand l'annuaire est refusé ou l'entrée vide")
  void nothingIsAttemptedWhenRefusedOrEmpty() {
    var insecure = directory("ldap://annuaire.ept.sn", false);
    var ready = directory("ldaps://annuaire.ept.sn", false);

    // Aucun appel réseau : la méthode rend vide tout de suite, sans tenter de résoudre un nom.
    assertThat(insecure.authenticate("khadija", "secret")).isEmpty();
    assertThat(ready.authenticate(null, "secret")).isEmpty();
    assertThat(ready.authenticate("khadija", "")).isEmpty();
  }

  @Test
  @DisplayName("Le nom distingué suit le gabarit configuré")
  void thedistinguishedNameFollowsTheConfiguredPattern() {
    assertThat(directory("ldaps://a", false).distinguishedName("khadija"))
        .isEqualTo("uid=khadija,ou=people,dc=ept,dc=sn");
    assertThat(directory("ldaps://a", false).distinguishedName("  boubacar  "))
        .isEqualTo("uid=boubacar,ou=people,dc=ept,dc=sn");
    assertThat(directory("ldaps://a", false).distinguishedName("  ")).isNull();
  }

  @Test
  @DisplayName("Un nom d'utilisateur ne peut pas fabriquer un autre nom distingué")
  void ausernameCannotForgeAnotherDistinguishedName() {
    // Sans échappement, « x,ou=admins » construirait un nom distingué qui n'est pas celui voulu.
    assertThat(JndiDirectory.escape("x,ou=admins")).isEqualTo("x\\,ou\\=admins");
    assertThat(JndiDirectory.escape("a+b")).isEqualTo("a\\+b");
    assertThat(JndiDirectory.escape("a\\b")).isEqualTo("a\\\\b");
    assertThat(JndiDirectory.escape("  ")).isNull();
    assertThat(directory("ldaps://a", false).distinguishedName("x,ou=admins"))
        .isEqualTo("uid=x\\,ou\\=admins,ou=people,dc=ept,dc=sn");
  }

  @Test
  @DisplayName("Un nom distingué ne peut pas fabriquer un autre filtre de recherche")
  void adistinguishedNameCannotForgeAnotherSearchFilter() {
    assertThat(JndiDirectory.escapeFilter("a*b")).isEqualTo("a\\2ab");
    assertThat(JndiDirectory.escapeFilter("(a)")).isEqualTo("\\28a\\29");
    assertThat(JndiDirectory.escapeFilter("a\\b")).isEqualTo("a\\5cb");
    assertThat(JndiDirectory.escapeFilter(null)).isEmpty();
  }

  @Test
  @DisplayName("Seul ldaps:// est tenu pour chiffré")
  void onlyLdapsIsConsideredEncrypted() {
    assertThat(directory("LDAPS://annuaire.ept.sn", false).secure()).isTrue();
    assertThat(directory("ldap://annuaire.ept.sn", false).secure()).isFalse();
    assertThat(directory("ldaps://annuaire.ept.sn:636", false).secure()).isTrue();
  }
}
