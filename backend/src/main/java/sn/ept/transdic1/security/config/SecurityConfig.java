package sn.ept.transdic1.security.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID"));
        config.setExposedHeaders(List.of("X-Correlation-ID"));
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity h, JwtFilter f) throws Exception {
        return h.csrf(c -> c.disable())
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
                    a.requestMatchers("/actuator/health", "/api/v1/auth/login")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
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
