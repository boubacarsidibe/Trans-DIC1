package sn.ept.transdic1.security.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173}") List<String> origins) {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    // Nécessaire pour que le navigateur envoie le cookie de renouvellement (origines explicites
    // uniquement).
    config.setAllowCredentials(true);
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID"));
    config.setExposedHeaders(List.of("X-Correlation-ID"));
    config.setMaxAge(3600L);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity h, JwtFilter f, LoginRateLimitFilter rateLimit)
      throws Exception {
    var docs =
        new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui.html"));
    return h.csrf(c -> c.disable())
        .headers(
            headers ->
                headers
                    .frameOptions(frame -> frame.deny())
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                    .permissionsPolicyHeader(
                        p -> p.policy("camera=(), microphone=(), geolocation=()"))
                    // API : aucune ressource n'est chargeable. Swagger UI (hors production)
                    // a besoin de ses propres scripts et styles.
                    .addHeaderWriter(
                        new DelegatingRequestMatcherHeaderWriter(
                            new NegatedRequestMatcher(docs),
                            new StaticHeadersWriter(
                                "Content-Security-Policy",
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
                    .addHeaderWriter(
                        new DelegatingRequestMatcherHeaderWriter(
                            docs,
                            new StaticHeadersWriter(
                                "Content-Security-Policy",
                                "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                                    + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                                    + "frame-ancestors 'none'"))))
        .cors(c -> {})
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) ->
                            write(response, HttpStatus.UNAUTHORIZED, "Authentification requise"))
                    .accessDeniedHandler(
                        (request, response, ex) ->
                            write(response, HttpStatus.FORBIDDEN, "Accès refusé")))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/v3/api-docs",
                        "/v3/api-docs.yaml",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(rateLimit, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(f, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static void write(HttpServletResponse response, HttpStatus status, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"status\":" + status.value() + ",\"error\":\"" + message + "\"}");
  }
}
