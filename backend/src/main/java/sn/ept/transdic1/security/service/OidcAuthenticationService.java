package sn.ept.transdic1.security.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Connexion unique OIDC (ARCH-09) : valide un jeton émis par le fournisseur d'identité (Keycloak ou
 * celui du CRI), en déduit le rôle de l'application et tient à jour le compte local correspondant
 * (les actions de l'application s'appuient sur l'utilisateur en base : auteur d'une prise en
 * charge, préférences, audit). Sans configuration OIDC, ce service ne fait rien et seule la
 * connexion locale existe.
 */
@Service
public class OidcAuthenticationService {
  private static final Logger LOG = LoggerFactory.getLogger(OidcAuthenticationService.class);

  /** Résultat d'une authentification OIDC réussie. */
  public record OidcUser(String username, Role role) {}

  private final ObjectProvider<JwtDecoder> decoder;
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final boolean enabled;
  private final String clientId;
  private final Map<Role, String> roleNames;

  public OidcAuthenticationService(
      ObjectProvider<JwtDecoder> decoder,
      UserRepository users,
      PasswordEncoder encoder,
      @Value("${auth.oidc.enabled:false}") boolean enabled,
      @Value("${auth.oidc.client-id:trans-dic1-web}") String clientId,
      @Value("${auth.oidc.role.admin:trans-dic1-admin}") String adminRole,
      @Value("${auth.oidc.role.noc:trans-dic1-noc}") String nocRole,
      @Value("${auth.oidc.role.viewer:trans-dic1-viewer}") String viewerRole) {
    this.decoder = decoder;
    this.users = users;
    this.encoder = encoder;
    this.enabled = enabled;
    this.clientId = clientId;
    this.roleNames =
        Map.of(Role.ADMIN, adminRole, Role.NOC_OPERATOR, nocRole, Role.VIEWER, viewerRole);
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Valide le jeton et retourne l'utilisateur, ou vide si le jeton n'est pas un jeton OIDC valide
   * ou ne porte aucun rôle de l'application.
   */
  @Transactional
  public Optional<OidcUser> authenticate(String token) {
    if (!enabled) {
      return Optional.empty();
    }
    JwtDecoder jwtDecoder = decoder.getIfAvailable();
    if (jwtDecoder == null) {
      return Optional.empty();
    }
    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(token);
    } catch (JwtException e) {
      return Optional.empty();
    }
    Optional<Role> role = role(jwt);
    String username = jwt.getClaimAsString("preferred_username");
    if (role.isEmpty() || username == null || username.isBlank()) {
      LOG.info("Jeton OIDC sans rôle Trans-DIC1 ou sans nom d'utilisateur : accès refusé");
      return Optional.empty();
    }
    AppUser user = provision(username.trim(), jwt.getClaimAsString("email"), role.get());
    if (!user.isActive()) {
      return Optional.empty();
    }
    return Optional.of(new OidcUser(user.getUsername(), user.getRole()));
  }

  /** Rôle le plus élevé trouvé parmi les rôles du royaume, du client et les groupes. */
  Optional<Role> role(Jwt jwt) {
    Collection<String> granted = new ArrayList<>();
    Object realm = jwt.getClaims().get("realm_access");
    if (realm instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
      roles.forEach(r -> granted.add(String.valueOf(r)));
    }
    Object resources = jwt.getClaims().get("resource_access");
    if (resources instanceof Map<?, ?> map
        && map.get(clientId) instanceof Map<?, ?> client
        && client.get("roles") instanceof Collection<?> roles) {
      roles.forEach(r -> granted.add(String.valueOf(r)));
    }
    List<String> groups = jwt.getClaimAsStringList("groups");
    if (groups != null) {
      groups.forEach(g -> granted.add(g.startsWith("/") ? g.substring(1) : g));
    }
    for (Role candidate : List.of(Role.ADMIN, Role.NOC_OPERATOR, Role.VIEWER)) {
      if (granted.contains(roleNames.get(candidate))) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /** Crée le compte local à la première connexion, puis aligne son rôle sur le fournisseur. */
  private AppUser provision(String username, String email, Role role) {
    return users
        .findByUsername(username)
        .map(
            existing -> {
              if (existing.getRole() != role) {
                existing.changeRole(role);
                users.save(existing);
              }
              return existing;
            })
        .orElseGet(
            () -> {
              // Mot de passe local aléatoire et inconnu : ce compte ne se connecte que par OIDC.
              String unusable = encoder.encode(UUID.randomUUID().toString());
              String address =
                  email == null || email.isBlank() ? username + "@oidc.invalid" : email;
              LOG.info("Compte créé à la première connexion OIDC : {}", username);
              return users.save(new AppUser(username, address, unusable, role));
            });
  }
}
