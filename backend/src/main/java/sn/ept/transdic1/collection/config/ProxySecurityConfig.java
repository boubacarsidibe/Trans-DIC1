package sn.ept.transdic1.collection.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import sn.ept.transdic1.collection.service.ProxyAuthService;

/**
 * Chaîne de sécurité propre aux proxys de collecte (ADR 0047) : un proxy s'authentifie avec son
 * jeton, jamais avec le JWT d'un utilisateur, et n'a accès qu'à /api/v1/proxy/**.
 *
 * <p>Chaîne distincte de celle des agents, et pas un rôle de plus dans celle-ci : un jeton d'agent
 * ne doit jamais ouvrir l'envoi groupé d'un site, ni un jeton de proxy l'envoi d'un équipement
 * qu'il ne relaie pas.
 */
@Configuration
public class ProxySecurityConfig {

  @Bean
  @Order(3)
  SecurityFilterChain proxyChain(HttpSecurity http, ProxyAuthService proxyAuth) throws Exception {
    return http.securityMatcher("/api/v1/proxy/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) -> write(response, 401, "Jeton de proxy invalide"))
                    .accessDeniedHandler(
                        (request, response, ex) -> write(response, 403, "Accès refusé")))
        .authorizeHttpRequests(a -> a.anyRequest().hasRole("PROXY"))
        .addFilterBefore(
            new ProxyTokenFilter(proxyAuth), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static void write(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message + "\"}");
  }

  /** Lit « Authorization: Bearer jeton-de-proxy » ; volontairement pas un bean, donc pas global. */
  static final class ProxyTokenFilter extends OncePerRequestFilter {
    private final ProxyAuthService proxyAuth;

    ProxyTokenFilter(ProxyAuthService proxyAuth) {
      this.proxyAuth = proxyAuth;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String header = request.getHeader("Authorization");
      if (header != null && header.startsWith("Bearer ")) {
        proxyAuth
            .authenticate(header.substring(7))
            .ifPresent(
                proxy ->
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                proxy, null, List.of(new SimpleGrantedAuthority("ROLE_PROXY")))));
      }
      chain.doFilter(request, response);
    }
  }
}
