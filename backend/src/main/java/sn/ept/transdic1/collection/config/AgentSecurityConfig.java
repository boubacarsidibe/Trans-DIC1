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
import sn.ept.transdic1.collection.service.AgentAuthService;

/**
 * Chaîne de sécurité propre aux agents : les serveurs supervisés s'authentifient avec le jeton de
 * leur agent, jamais avec le JWT d'un utilisateur, et n'ont accès qu'à /api/v1/agent/**.
 */
@Configuration
public class AgentSecurityConfig {

  @Bean
  @Order(1)
  SecurityFilterChain agentChain(HttpSecurity http, AgentAuthService agentAuth) throws Exception {
    return http.securityMatcher("/api/v1/agent/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) -> write(response, 401, "Jeton d'agent invalide"))
                    .accessDeniedHandler(
                        (request, response, ex) -> write(response, 403, "Accès refusé")))
        .authorizeHttpRequests(a -> a.anyRequest().hasRole("AGENT"))
        .addFilterBefore(
            new AgentTokenFilter(agentAuth), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static void write(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message + "\"}");
  }

  /** Lit « Authorization: Bearer jeton-d-agent » ; volontairement pas un bean, donc pas global. */
  static final class AgentTokenFilter extends OncePerRequestFilter {
    private final AgentAuthService agentAuth;

    AgentTokenFilter(AgentAuthService agentAuth) {
      this.agentAuth = agentAuth;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String header = request.getHeader("Authorization");
      if (header != null && header.startsWith("Bearer ")) {
        agentAuth
            .authenticate(header.substring(7))
            .ifPresent(
                agent ->
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                agent, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")))));
      }
      chain.doFilter(request, response);
    }
  }
}
