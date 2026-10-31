package sn.ept.transdic1.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.security.dto.*;
import sn.ept.transdic1.security.service.AuthService;

@Tag(
    name = "Authentification",
    description =
        "Jeton d'accès (corps de la réponse) et jeton de renouvellement (cookie HttpOnly).")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private static final String COOKIE_PATH = "/api/v1/auth";

  private final AuthService service;
  private final String cookieName;
  private final boolean cookieSecure;
  private final List<String> allowedOrigins;
  private final boolean localLogin;

  public AuthController(
      AuthService service,
      @Value("${security.refresh.cookie-name:trans_dic1_refresh}") String cookieName,
      @Value("${security.refresh.cookie-secure:true}") boolean cookieSecure,
      @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173}") List<String> allowedOrigins,
      @Value("${auth.local.enabled:true}") boolean localLogin) {
    this.service = service;
    this.cookieName = cookieName;
    this.cookieSecure = cookieSecure;
    this.allowedOrigins = allowedOrigins;
    this.localLogin = localLogin;
  }

  @Operation(
      summary = "Se connecter",
      description =
          "Retourne un jeton JWT court à envoyer dans l'en-tête Authorization: Bearer et dépose le"
              + " jeton de renouvellement dans un cookie HttpOnly.")
  @SecurityRequirements
  @ApiResponse(responseCode = "400", description = "Requête invalide")
  @ApiResponse(
      responseCode = "401",
      description = "Identifiants invalides ou compte verrouillé (réponse identique par sécurité)")
  @ApiResponse(
      responseCode = "429",
      description = "Trop de tentatives depuis cette adresse, voir l'en-tête Retry-After")
  @ApiResponse(
      responseCode = "403",
      description = "Connexion locale désactivée : seule la connexion unique OIDC est ouverte")
  @PostMapping("/login")
  ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    if (!localLogin) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Connexion locale désactivée : utilisez la connexion unique.");
    }
    return respond(service.login(request, http.getRemoteAddr()));
  }

  @Operation(
      summary = "Renouveler le jeton d'accès",
      description =
          "Lit le cookie de renouvellement, le remplace par un nouveau (rotation) et retourne un"
              + " nouveau jeton d'accès. Un jeton déjà utilisé révoque toute la connexion.")
  @SecurityRequirements
  @ApiResponse(responseCode = "401", description = "Cookie absent, expiré, révoqué ou déjà utilisé")
  @ApiResponse(responseCode = "403", description = "Origine de la requête non autorisée")
  @PostMapping("/refresh")
  ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = "${security.refresh.cookie-name:trans_dic1_refresh}", required = false)
          String refreshToken,
      HttpServletRequest http) {
    requireAllowedOrigin(http);
    try {
      return respond(service.refresh(refreshToken, http.getRemoteAddr()));
    } catch (ResponseStatusException e) {
      // Le cookie inutilisable est effacé pour que le navigateur ne le renvoie plus.
      return ResponseEntity.status(e.getStatusCode())
          .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
          .build();
    }
  }

  @Operation(
      summary = "Se déconnecter",
      description = "Révoque la connexion et efface le cookie. Sans effet si le cookie est absent.")
  @SecurityRequirements
  @ApiResponse(responseCode = "204", description = "Déconnecté")
  @ApiResponse(responseCode = "403", description = "Origine de la requête non autorisée")
  @PostMapping("/logout")
  ResponseEntity<Void> logout(
      @CookieValue(name = "${security.refresh.cookie-name:trans_dic1_refresh}", required = false)
          String refreshToken,
      HttpServletRequest http) {
    requireAllowedOrigin(http);
    service.logout(refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
        .build();
  }

  private ResponseEntity<TokenResponse> respond(AuthSession session) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            cookie(session.refreshToken(), Duration.ofSeconds(session.refreshMaxAgeSeconds()))
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(session.token());
  }

  private ResponseCookie cookie(String value, Duration maxAge) {
    return ResponseCookie.from(cookieName, value)
        .httpOnly(true)
        .secure(cookieSecure)
        .sameSite("Strict")
        .path(COOKIE_PATH)
        .maxAge(maxAge)
        .build();
  }

  /**
   * Défense contre le CSRF sur les seuls endpoints qui s'appuient sur le cookie : SameSite=Strict,
   * plus une origine connue obligatoire (un navigateur envoie toujours Origin sur un POST).
   */
  private void requireAllowedOrigin(HttpServletRequest http) {
    String origin = http.getHeader(HttpHeaders.ORIGIN);
    if (origin == null || !allowedOrigins.contains(origin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Origine non autorisée");
    }
  }
}
