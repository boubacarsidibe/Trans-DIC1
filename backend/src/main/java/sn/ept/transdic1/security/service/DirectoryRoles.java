package sn.ept.transdic1.security.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import sn.ept.transdic1.security.domain.Role;

/**
 * Traduction des groupes de l'annuaire en rôles de l'application (ADR 0046).
 *
 * <p>Trois décisions tiennent ici, et ce sont elles qui comptent :
 *
 * <ol>
 *   <li><b>Le rôle le plus fort gagne.</b> Quelqu'un qui est à la fois dans le groupe des
 *       administrateurs et dans celui des lecteurs est administrateur. L'inverse — le rôle le plus
 *       faible — serait une surprise désagréable le jour où on ajoute quelqu'un à un groupe large.
 *   <li><b>Aucun groupe reconnu veut dire aucun accès.</b> Pas de rôle par défaut : donner «
 *       lecteur » à toute personne de l'annuaire ouvrirait la supervision du campus à tous les
 *       étudiants. C'est le genre de défaut qu'on ne découvre qu'après.
 *   <li><b>La comparaison ignore la casse et les espaces</b>, parce qu'un nom de groupe se recopie
 *       à la main depuis une console d'administration et qu'un « NOC » contre « noc » bloquerait
 *       l'accès sans que rien ne le dise.
 * </ol>
 *
 * <p>Calcul pur, sans Spring ni réseau.
 */
public final class DirectoryRoles {

  private DirectoryRoles() {}

  /**
   * Rôle à donner, d'après les groupes de la personne.
   *
   * @param mapping nom de groupe attendu pour chaque rôle ; une entrée vide ou absente désactive ce
   *     rôle
   * @return le rôle le plus fort reconnu, ou vide si aucun groupe ne correspond
   */
  public static Optional<Role> resolve(List<String> groups, Map<Role, String> mapping) {
    if (groups == null || groups.isEmpty() || mapping == null) {
      return Optional.empty();
    }
    // L'ordre de déclaration de Role va du plus fort au plus faible : on prend le premier trouvé.
    for (Role role : Role.values()) {
      String expected = normalise(mapping.get(role));
      if (expected == null) {
        continue;
      }
      for (String group : groups) {
        if (expected.equals(normalise(group))) {
          return Optional.of(role);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Nom court d'un groupe : le premier composant d'un nom distingué, ou la valeur telle quelle.
   *
   * <p>Un annuaire rend « cn=noc,ou=groups,dc=ept,dc=sn » là où l'administrateur a écrit « noc »
   * dans la configuration. Comparer les chaînes entières obligerait à recopier le nom distingué
   * complet, qui change dès qu'on déplace une unité d'organisation.
   */
  public static String shortName(String group) {
    if (group == null || group.isBlank()) {
      return null;
    }
    String first = group.split(",")[0].trim();
    int equals = first.indexOf('=');
    String name = equals < 0 ? first : first.substring(equals + 1);
    return name.isBlank() ? null : name.trim();
  }

  /** Noms courts d'une liste de groupes, sans doublon ni valeur vide. */
  public static List<String> shortNames(List<String> groups) {
    if (groups == null) {
      return List.of();
    }
    return groups.stream()
        .map(DirectoryRoles::shortName)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
  }

  private static String normalise(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return shortName(value) == null ? null : shortName(value).toLowerCase(Locale.ROOT);
  }
}
