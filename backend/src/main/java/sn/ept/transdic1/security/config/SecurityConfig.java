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

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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
