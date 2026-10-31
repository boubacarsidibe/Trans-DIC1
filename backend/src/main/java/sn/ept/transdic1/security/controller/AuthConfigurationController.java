package sn.ept.transdic1.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentification")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigurationController {
  /** Modes de connexion proposés à l'écran ; aucune valeur secrète. */
  public record Configuration(boolean localLogin, Oidc oidc) {}

  public record Oidc(boolean enabled, String issuer, String clientId, String scopes) {}

  /** L'utilisateur connecté, tel que l'application le voit (rôle déjà converti). */
  public record Me(String username, String role) {}

  private final Configuration configuration;

  public AuthConfigurationController(
      @Value("${auth.local.enabled:true}") boolean localLogin,
      @Value("${auth.oidc.enabled:false}") boolean oidcEnabled,
      @Value("${auth.oidc.issuer-uri:}") String issuer,
      @Value("${auth.oidc.client-id:trans-dic1-web}") String clientId,
      @Value("${auth.oidc.scopes:openid profile email}") String scopes) {
    this.configuration =
        new Configuration(
            localLogin,
            new Oidc(
                oidcEnabled, oidcEnabled ? issuer : null, oidcEnabled ? clientId : null, scopes));
  }

  @Operation(
      summary = "Modes de connexion",
      description =
          "Connexion locale et/ou connexion unique OIDC (Authorization Code + PKCE) ; l'écran de"
              + " connexion s'adapte à cette réponse.")
  @SecurityRequirements
  @GetMapping("/configuration")
  public Configuration configuration() {
    return configuration;
  }

  @Operation(summary = "Utilisateur connecté et son rôle dans l'application")
  @GetMapping("/moi")
  public Me me(Authentication authentication) {
    String role =
        authentication.getAuthorities().stream()
            .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
            .findFirst()
            .orElse("VIEWER");
    return new Me(authentication.getName(), role);
  }
}
