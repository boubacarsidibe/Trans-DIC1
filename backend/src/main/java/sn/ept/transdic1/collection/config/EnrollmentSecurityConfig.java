package sn.ept.transdic1.collection.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import sn.ept.transdic1.collection.service.EnrollmentAuthService;

/**
 * Chaîne de sécurité de l'inscription des agents (ADR 0054), sur le modèle de celle des agents :
 * elle ne répond qu'à {@code /api/v1/agent/enroll} et n'accepte que les jetons d'inscription.
 *
 * <p>Ordre 0, avant la chaîne des agents (ordre 1, {@code /api/v1/agent/**}) : un jeton
 * d'inscription n'est reconnu nulle part ailleurs, donc n'ouvre ni l'envoi de mesures ni aucune
 * autre API ; un jeton d'agent n'est pas reconnu ici, donc ne permet pas de s'inscrire.
 */
@Configuration
public class EnrollmentSecurityConfig {
  public static final String ENROLL_PATH = "/api/v1/agent/enroll";

  @Bean
  @Order(0)
  SecurityFilterChain enrollmentChain(
      HttpSecurity http,
      EnrollmentAuthService enrollmentAuth,
      @Value("${collection.enrollment.rate-limit-per-minute:30}") long perMinute)
      throws Exception {
    return http.securityMatcher(ENROLL_PATH)
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) ->
                            write(response, 401, "Jeton d'inscription invalide"))
                    .accessDeniedHandler(
                        (request, response, ex) -> write(response, 403, "Accès refusé")))
        .authorizeHttpRequests(a -> a.anyRequest().hasRole("ENROLL"))
        .addFilterBefore(
            new EnrollmentTokenFilter(enrollmentAuth), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new EnrollmentRateLimitFilter(perMinute), EnrollmentTokenFilter.class)
        .build();
  }

  private static void write(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message + "\"}");
  }

  /** Lit « Authorization: Bearer tdic1e_… » ; volontairement pas un bean, donc pas global. */
  static final class EnrollmentTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(EnrollmentTokenFilter.class);
    private final EnrollmentAuthService enrollmentAuth;

    EnrollmentTokenFilter(EnrollmentAuthService enrollmentAuth) {
      this.enrollmentAuth = enrollmentAuth;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String header = request.getHeader("Authorization");
      if (header != null && header.startsWith("Bearer ")) {
        var principal = enrollmentAuth.authenticate(header.substring(7));
        principal.ifPresent(
            enrollment ->
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                            enrollment, null, List.of(new SimpleGrantedAuthority("ROLE_ENROLL")))));
        if (principal.isEmpty()) {
          // Jeton inconnu : rien à rattacher dans l'audit, une ligne de sécurité suffit.
          log.warn("security_event action=ENROLLMENT_UNKNOWN_TOKEN");
        }
      }
      chain.doFilter(request, response);
    }
  }
}
