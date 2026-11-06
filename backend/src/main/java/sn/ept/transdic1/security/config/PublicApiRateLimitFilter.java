package sn.ept.transdic1.security.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite les lectures de l'API publique sans connexion ({@code /api/v1/public/**}, page de statut,
 * ADR 0026) par adresse : {@code statuspage.public.rate-limit-per-minute} requêtes par minute (60
 * par défaut), puis 429 avec Retry-After. Comme la limitation des connexions, la clé est l'adresse
 * de la connexion TCP ({@code getRemoteAddr}) : X-Forwarded-For n'est lu que si l'exploitation a
 * activé {@code server.forward-headers-strategy=native} derrière le reverse proxy de confiance, qui
 * remplace cet en-tête (ADR 0011). Un client direct ne peut donc pas choisir sa clé.
 */
@Component
public class PublicApiRateLimitFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(PublicApiRateLimitFilter.class);
  public static final String PUBLIC_PREFIX = "/api/v1/public/";
  private static final int MAX_TRACKED_ADDRESSES = 10_000;
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final long perMinute;
  private final Map<String, Bucket> buckets =
      new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
          return size() > MAX_TRACKED_ADDRESSES;
        }
      };

  public PublicApiRateLimitFilter(
      @Value("${statuspage.public.rate-limit-per-minute:60}") long perMinute) {
    this.perMinute = Math.max(1, perMinute);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(PUBLIC_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var probe = bucketFor(request.getRemoteAddr()).tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      chain.doFilter(request, response);
      return;
    }
    long retryAfter =
        Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
    log.warn("security_event action=PUBLIC_RATE_LIMITED retryAfterSeconds={}", retryAfter);
    response.setStatus(429);
    response.setHeader("Retry-After", Long.toString(retryAfter));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write("{\"status\":429,\"error\":\"Trop de requêtes. Réessayez plus tard.\"}");
  }

  private Bucket bucketFor(String address) {
    synchronized (buckets) {
      return buckets.computeIfAbsent(
          address,
          k ->
              Bucket.builder()
                  .addLimit(
                      Bandwidth.builder()
                          .capacity(perMinute)
                          .refillGreedy(perMinute, WINDOW)
                          .build())
                  .build());
    }
  }
}
