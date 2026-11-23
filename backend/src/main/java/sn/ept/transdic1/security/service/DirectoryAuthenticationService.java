package sn.ept.transdic1.security.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.port.Directory;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Connexion par l'annuaire de l'école (ADR 0046) : l'annuaire dit si le mot de passe est bon et à
 * quels groupes la personne appartient ; la plateforme en déduit son rôle et tient à jour le compte
 * local correspondant.
 *
 * <p><b>Pourquoi un compte local malgré tout.</b> Toutes les actions de l'application s'appuient
 * sur l'utilisateur en base : auteur d'une prise en charge, assigné d'une alerte, préférences de
 * notification, journal d'audit. Un compte d'annuaire est donc créé à la première connexion et mis
 * à jour aux suivantes, exactement comme pour la connexion unique OIDC (ADR 0019).
 *
 * <p><b>Ce compte n'a pas de mot de passe utilisable.</b> La colonne reste obligatoire, on y écrit
 * une valeur aléatoire que personne ne peut présenter. Garder un second secret aurait annulé
 * l'intérêt de déléguer : un mot de passe local périmé continuerait d'ouvrir la porte après un
 * changement côté annuaire.
 *
 * <p><b>Le rôle est relu à chaque connexion.</b> Quelqu'un retiré du groupe des administrateurs
 * perd ses droits à sa prochaine connexion, sans intervention. Mais il les garde jusque-là : une
 * session en cours n'est pas coupée, et c'est une limite à connaître.
 */
@Service
public class DirectoryAuthenticationService {
  private static final Logger LOG = LoggerFactory.getLogger(DirectoryAuthenticationService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Directory directory;
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final Map<Role, String> groups;

  public DirectoryAuthenticationService(
      Directory directory,
      UserRepository users,
      PasswordEncoder passwords,
      @Value("${ldap.group-admin:}") String adminGroup,
      @Value("${ldap.group-operator:}") String operatorGroup,
      @Value("${ldap.group-viewer:}") String viewerGroup) {
    this.directory = directory;
    this.users = users;
    this.passwords = passwords;
    var mapping = new EnumMap<Role, String>(Role.class);
    mapping.put(Role.ADMIN, adminGroup);
    mapping.put(Role.NOC_OPERATOR, operatorGroup);
    mapping.put(Role.VIEWER, viewerGroup);
    this.groups = java.util.Collections.unmodifiableMap(mapping);
  }

  /** Vrai si un annuaire est configuré et exploitable. */
  public boolean enabled() {
    return directory.enabled();
  }

  /** Pourquoi une connexion par l'annuaire a échoué, pour le journal de sécurité. */
  public enum Refusal {
    /** Mot de passe refusé par l'annuaire, ou annuaire injoignable. */
    REJECTED,
    /** Mot de passe accepté, mais aucun groupe reconnu : la personne n'a aucun droit ici. */
    NO_ROLE
  }

  /** Résultat d'une tentative : le compte à jour, ou la raison du refus. */
  public record Attempt(AppUser user, Refusal refusal) {

    public boolean succeeded() {
      return user != null;
    }

    static Attempt refused(Refusal refusal) {
      return new Attempt(null, refusal);
    }
  }

  /**
   * Vérifie le mot de passe auprès de l'annuaire et met à jour le compte local.
   *
   * <p>Aucun groupe reconnu vaut refus, et non « lecteur par défaut » : donner un accès en lecture
   * à toute personne figurant dans l'annuaire ouvrirait la supervision du campus à tous les
   * étudiants.
   */
  @Transactional
  public Attempt authenticate(String username, String password) {
    Optional<Directory.DirectoryUser> found = directory.authenticate(username, password);
    if (found.isEmpty()) {
      return Attempt.refused(Refusal.REJECTED);
    }
    Directory.DirectoryUser person = found.get();
    Optional<Role> role = DirectoryRoles.resolve(person.groups(), groups);
    if (role.isEmpty()) {
      // Les groupes sont journalisés : sans eux, un administrateur ne saurait pas quel nom écrire
      // dans la configuration, et chercherait longtemps.
      LOG.warn(
          "Annuaire : mot de passe accepté pour {} mais aucun groupe reconnu parmi {}",
          username,
          person.groups());
      return Attempt.refused(Refusal.NO_ROLE);
    }
    return new Attempt(provision(person, role.get()), null);
  }

  /** Crée le compte à la première connexion, le met à jour aux suivantes. */
  private AppUser provision(Directory.DirectoryUser person, Role role) {
    AppUser user = users.findByUsername(person.username()).orElseGet(() -> create(person, role));
    if (!user.isDirectory()) {
      // Un compte local existe déjà sous ce nom : on ne le transforme pas en compte d'annuaire à
      // son insu. L'administrateur doit trancher, et le journal le lui dit.
      LOG.warn(
          "Annuaire : un compte local porte déjà le nom {} ; la connexion par l'annuaire est"
              + " refusée pour ne pas le remplacer",
          person.username());
      return null;
    }
    if (!user.isActive()) {
      // Un compte désactivé à la main le reste : la décision de l'administrateur prime sur
      // l'annuaire, qui ne sait rien de nos raisons de fermer un accès.
      LOG.info(
          "Annuaire : le compte {} est désactivé localement, la connexion est refusée",
          person.username());
      return null;
    }
    if (user.getRole() != role) {
      // Le rôle est relu à chaque connexion : quelqu'un retiré du groupe des administrateurs
      // perd ses droits dès la prochaine.
      user.setRole(role);
      users.save(user);
    }
    // L'adresse n'est reprise qu'à la création : elle est unique en base, et la corriger ici
    // ferait échouer la connexion le jour où deux comptes se la disputent. La modifier ensuite
    // appartient à l'administration des comptes.
    return user;
  }

  private AppUser create(Directory.DirectoryUser person, Role role) {
    var user =
        new AppUser(person.username(), email(person), passwords.encode(unusablePassword()), role);
    user.setDirectory(true);
    LOG.info("Annuaire : création du compte {} avec le rôle {}", person.username(), role);
    return users.save(user);
  }

  /**
   * Adresse de la personne, ou une adresse de substitution si l'annuaire n'en donne pas : la
   * colonne est obligatoire et unique, et refuser la connexion pour une adresse manquante serait
   * disproportionné.
   */
  private static String email(Directory.DirectoryUser person) {
    String given = person.email();
    if (given != null && !given.isBlank() && given.contains("@")) {
      return given.trim().toLowerCase(java.util.Locale.ROOT);
    }
    return person.username().toLowerCase(java.util.Locale.ROOT) + "@annuaire.invalid";
  }

  /** Mot de passe que personne ne peut présenter : le compte n'a pas de secret local. */
  private static String unusablePassword() {
    byte[] bytes = new byte[48];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Noms de groupes attendus, pour les écrans d'administration et les tests. */
  public List<String> expectedGroups() {
    return groups.values().stream().filter(name -> name != null && !name.isBlank()).toList();
  }
}
