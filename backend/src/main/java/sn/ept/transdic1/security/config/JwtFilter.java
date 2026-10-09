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

@Component
public class JwtFilter extends OncePerRequestFilter {
  private final JwtService jwt;

  public JwtFilter(JwtService j) {
    jwt = j;
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
      } catch (IllegalArgumentException ignored) {
      }
    c.doFilter(r, s);
  }
}
