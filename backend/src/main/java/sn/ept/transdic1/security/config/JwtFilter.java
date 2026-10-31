package sn.ept.transdic1.security.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sn.ept.transdic1.security.service.AccountService;
import sn.ept.transdic1.security.service.JwtService;
import sn.ept.transdic1.security.service.OidcAuthenticationService;

@Component
public class JwtFilter extends OncePerRequestFilter {
  /** Seules requêtes permises tant que le mot de passe temporaire n'est pas changé. */
  private static final String AUTH_PATHS = "/api/v1/auth/";

  private final JwtService jwt;
  private final OidcAuthenticationService oidc;
  private final AccountService accounts;

  public JwtFilter(JwtService j, OidcAuthenticationService oidc, AccountService accounts) {
    jwt = j;
    this.oidc = oidc;
    this.accounts = accounts;
  }

  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain c)
      throws ServletException, IOException {
    String h = r.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer "))
      try {
        var x = jwt.verify(h.substring(7));
        // L'état du compte est relu à chaque requête (FEAT-01) : un compte désactivé ou dont le
        // rôle a changé n'est plus accepté, même avec un jeton encore valide. Sans
        // authentification, la requête finit en 401 et le client se reconnecte.
        var status = accounts.status(x.username()).orElse(null);
        if (status != null && status.active() && status.role() == x.role()) {
          if (status.passwordChangeRequired() && !r.getRequestURI().startsWith(AUTH_PATHS)) {
            passwordChangeRequired(s);
            return;
          }
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new UsernamePasswordAuthenticationToken(
                      x.username(), null, List.of(new SimpleGrantedAuthority("ROLE_" + x.role()))));
        }
      } catch (IllegalArgumentException notLocal) {
        // Pas un jeton local : peut-être un jeton du fournisseur OIDC (connexion unique).
        oidc.authenticate(h.substring(7))
            .ifPresent(
                user ->
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                user.username(),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + user.role())))));
      }
    c.doFilter(r, s);
  }

  private static void passwordChangeRequired(HttpServletResponse s) throws IOException {
    s.setStatus(HttpServletResponse.SC_FORBIDDEN);
    s.setContentType(MediaType.APPLICATION_JSON_VALUE);
    s.setCharacterEncoding("UTF-8");
    s.getWriter()
        .write(
            "{\"status\":403,\"error\":\"Changez votre mot de passe temporaire avant de"
                + " continuer\",\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
  }
}
