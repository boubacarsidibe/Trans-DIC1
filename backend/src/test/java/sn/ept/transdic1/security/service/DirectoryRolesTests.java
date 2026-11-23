package sn.ept.transdic1.security.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.security.domain.Role;

/**
 * Traduction des groupes de l'annuaire en rôles (ADR 0046) : qui obtient quoi, et surtout qui
 * n'obtient rien.
 */
class DirectoryRolesTests {

  private static Map<Role, String> mapping(String admin, String operator, String viewer) {
    var map = new EnumMap<Role, String>(Role.class);
    map.put(Role.ADMIN, admin);
    map.put(Role.NOC_OPERATOR, operator);
    map.put(Role.VIEWER, viewer);
    return map;
  }

  private static final Map<Role, String> EPT = mapping("cri-admins", "cri-noc", "cri-lecteurs");

  @Test
  @DisplayName("Un groupe reconnu donne son rôle")
  void arecognisedGroupGivesItsRole() {
    assertThat(DirectoryRoles.resolve(List.of("cri-noc"), EPT)).contains(Role.NOC_OPERATOR);
    assertThat(DirectoryRoles.resolve(List.of("cri-admins"), EPT)).contains(Role.ADMIN);
    assertThat(DirectoryRoles.resolve(List.of("cri-lecteurs"), EPT)).contains(Role.VIEWER);
  }

  @Test
  @DisplayName("Ce qui compte le plus : aucun groupe reconnu veut dire aucun accès")
  void whatMattersMostNorecognisedGroupMeansNoaccess() {
    // Un rôle par défaut aurait ouvert la supervision du campus à toute personne figurant dans
    // l'annuaire — tous les étudiants compris.
    assertThat(DirectoryRoles.resolve(List.of("etudiants", "promo-2026"), EPT)).isEmpty();
    assertThat(DirectoryRoles.resolve(List.of(), EPT)).isEmpty();
    assertThat(DirectoryRoles.resolve(null, EPT)).isEmpty();
  }

  @Test
  @DisplayName("Le rôle le plus fort gagne, pas le premier venu")
  void thestrongestRoleWinsNotTheFirstFound() {
    // Quelqu'un dans le groupe des lecteurs et celui des administrateurs est administrateur :
    // l'inverse serait une surprise désagréable le jour où on l'ajoute à un groupe large.
    assertThat(DirectoryRoles.resolve(List.of("cri-lecteurs", "cri-admins"), EPT))
        .contains(Role.ADMIN);
    assertThat(DirectoryRoles.resolve(List.of("cri-admins", "cri-lecteurs"), EPT))
        .contains(Role.ADMIN);
    assertThat(DirectoryRoles.resolve(List.of("cri-noc", "cri-lecteurs"), EPT))
        .contains(Role.NOC_OPERATOR);
  }

  @Test
  @DisplayName("La casse et les espaces ne bloquent pas l'accès")
  void caseAndSpacesDoNotBlockAccess() {
    // Un nom de groupe se recopie à la main depuis une console d'administration : « NOC » contre
    // « noc » bloquerait l'accès sans que rien ne le dise.
    assertThat(DirectoryRoles.resolve(List.of("CRI-NOC"), EPT)).contains(Role.NOC_OPERATOR);
    assertThat(DirectoryRoles.resolve(List.of("  cri-noc  "), EPT)).contains(Role.NOC_OPERATOR);
    assertThat(DirectoryRoles.resolve(List.of("cri-noc"), mapping(null, " CRI-Noc ", null)))
        .contains(Role.NOC_OPERATOR);
  }

  @Test
  @DisplayName("Un nom distingué est comparé sur son nom court")
  void adistinguishedNameIsComparedOnItsShortName() {
    // L'annuaire rend le nom distingué complet ; l'administrateur a écrit « cri-noc ». Comparer
    // les chaînes entières obligerait à recopier un nom qui change dès qu'on déplace une unité
    // d'organisation.
    assertThat(DirectoryRoles.resolve(List.of("cn=cri-noc,ou=groups,dc=ept,dc=sn"), EPT))
        .contains(Role.NOC_OPERATOR);
    assertThat(DirectoryRoles.shortName("cn=cri-noc,ou=groups,dc=ept,dc=sn")).isEqualTo("cri-noc");
    assertThat(DirectoryRoles.shortName("cri-noc")).isEqualTo("cri-noc");
    assertThat(DirectoryRoles.shortName("  CN=Admins , ou=x ")).isEqualTo("Admins");
  }

  @Test
  @DisplayName("Un rôle sans groupe configuré n'est jamais donné")
  void aroleWithoutAconfiguredGroupIsNeverGiven() {
    var onlyAdmin = mapping("cri-admins", null, "  ");

    assertThat(DirectoryRoles.resolve(List.of("cri-noc"), onlyAdmin)).isEmpty();
    assertThat(DirectoryRoles.resolve(List.of("cri-lecteurs"), onlyAdmin)).isEmpty();
    assertThat(DirectoryRoles.resolve(List.of("cri-admins"), onlyAdmin)).contains(Role.ADMIN);
  }

  @Test
  @DisplayName("Les noms courts sont nettoyés et dédoublonnés")
  void theshortNamesAreCleanedAndDeduplicated() {
    var names =
        DirectoryRoles.shortNames(
            java.util.Arrays.asList("cn=noc,ou=groups", "noc", null, "   ", "cn=admins,ou=groups"));

    assertThat(names).containsExactly("noc", "admins");
    assertThat(DirectoryRoles.shortNames(null)).isEmpty();
  }

  @Test
  @DisplayName("Sans correspondance configurée, personne n'a de rôle")
  void withoutAnyconfiguredMappingNobodyHasArole() {
    assertThat(DirectoryRoles.resolve(List.of("cri-admins"), Map.of())).isEmpty();
    assertThat(DirectoryRoles.resolve(List.of("cri-admins"), null)).isEmpty();
  }
}
