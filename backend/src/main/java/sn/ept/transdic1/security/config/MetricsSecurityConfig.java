package sn.ept.transdic1.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Chaîne de sécurité de l'endpoint Prometheus : seul le collecteur de métriques y accède, avec le
 * jeton METRICS_SCRAPE_TOKEN. Un JWT d'utilisateur ne l'ouvre pas. Sans jeton configuré, l'endpoint
 * est fermé à tous : les métriques révèlent le volume d'équipements et d'alertes.
 */
@Configuration
public class MetricsSecurityConfig {
  /** En dessous, un jeton se devine trop facilement : il est alors ignoré. */
  static final int MIN_TOKEN_LENGTH = 32;

  @Bean
  @Order(2)
  SecurityFilterChain metricsChain(
      HttpSecurity http, @Value("${METRICS_SCRAPE_TOKEN:}") String token) throws Exception {
    return http.securityMatcher("/actuator/prometheus")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) ->
                            write(response, 401, "Jeton de collecte des métriques requis"))
                    .accessDeniedHandler(
                        (request, response, ex) -> write(response, 403, "Accès refusé")))
        .authorizeHttpRequests(a -> a.anyRequest().hasRole("METRICS"))
        .addFilterBefore(new ScrapeTokenFilter(token), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static void write(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message + "\"}");
  }

  /** Compare « Authorization: Bearer jeton » en temps constant ; volontairement pas un bean. */
  static final class ScrapeTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;

    ScrapeTokenFilter(String token) {
      this.expected =
          token != null && token.length() >= MIN_TOKEN_LENGTH
              ? token.getBytes(StandardCharsets.UTF_8)
              : null;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String header = request.getHeader("Authorization");
      if (expected != null
          && header != null
          && header.startsWith("Bearer ")
          && MessageDigest.isEqual(
              expected, header.substring(7).getBytes(StandardCharsets.UTF_8))) {
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    "prometheus", null, List.of(new SimpleGrantedAuthority("ROLE_METRICS"))));
      }
      chain.doFilter(request, response);
    }
  }
}
