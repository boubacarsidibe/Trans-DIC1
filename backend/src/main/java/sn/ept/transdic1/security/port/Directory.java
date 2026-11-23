package sn.ept.transdic1.security.port;

import java.util.List;
import java.util.Optional;

/**
 * Annuaire d'entreprise (LDAP ou Active Directory), vu comme une seule question : <b>ce mot de
 * passe est-il le bon, et à quels groupes appartient cette personne ?</b> (ADR 0046).
 *
 * <p>Ce port existe pour une raison précise : l'appel à l'annuaire ne peut pas être éprouvé sans
 * annuaire, et il n'y en a pas sur le poste de développement. Tout ce qui se raisonne — construire
 * le nom distingué, traduire les groupes en rôles, décider si un compte est local ou délégué,
 * refuser un mot de passe en clair sur le réseau — est donc écrit en dehors de l'implémentation, et
 * testé avec un annuaire de mentir. Ne reste derrière ce port que le strict dialogue réseau.
 */
public interface Directory {

  /** Vrai si un annuaire est configuré ; faux, et seule la connexion locale existe. */
  boolean enabled();

  /**
   * Vérifie le mot de passe auprès de l'annuaire.
   *
   * @return la personne et ses groupes si le mot de passe est bon, vide sinon — y compris quand
   *     l'annuaire est injoignable : on ne distingue pas les causes d'échec vers l'appelant, c'est
   *     le journal qui le fait
   */
  Optional<DirectoryUser> authenticate(String username, String password);

  /**
   * Une personne telle que l'annuaire la décrit.
   *
   * @param groups noms courts des groupes (le dernier composant du nom distingué, ou la valeur
   *     telle quelle si ce n'en est pas un)
   */
  record DirectoryUser(String username, String email, List<String> groups) {

    public DirectoryUser {
      groups = groups == null ? List.of() : List.copyOf(groups);
    }
  }
}
