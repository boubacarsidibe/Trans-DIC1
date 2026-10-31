package sn.ept.transdic1.security.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sn.ept.transdic1.security.service.JwtService;
import sn.ept.transdic1.security.service.OidcAuthenticationService;

@Component
public class JwtFilter extends OncePerRequestFilter {
  private final JwtService jwt;
  private final OidcAuthenticationService oidc;

  public JwtFilter(JwtService j, OidcAuthenticationService oidc) {
    jwt = j;
    this.oidc = oidc;
  }

  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain c)
      throws ServletException, IOException {
    String h = r.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer "))
      try {
        var x = jwt.verify(h.substring(7));
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    x.username(), null, List.of(new SimpleGrantedAuthority("ROLE_" + x.role()))));
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
}
