package sn.ept.transdic1.collection.config;

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
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite les demandes d'inscription par adresse (ADR 0054), avant toute lecture du jeton : essayer
 * des jetons au hasard coûte une requête refusée par minute et par adresse au-delà du seuil.
 *
 * <p>Volontairement pas un bean : il ne s'applique qu'à la chaîne d'inscription. Au-delà du seuil,
 * 429 avec Retry-After ; l'agent attend et réessaie, il ne perd rien. Le seuil par défaut (30 par
 * minute et par adresse) laisse passer un bâtiment entier derrière une même adresse NAT en quelques
 * minutes, ce que les réessais étalés de l'agent absorbent.
 */
public class EnrollmentRateLimitFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(EnrollmentRateLimitFilter.class);
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

  public EnrollmentRateLimitFilter(long perMinute) {
    this.perMinute = Math.max(1, perMinute);
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
    log.warn("security_event action=ENROLLMENT_RATE_LIMITED retryAfterSeconds={}", retryAfter);
    response.setStatus(429);
    response.setHeader("Retry-After", Long.toString(retryAfter));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(
            "{\"status\":429,\"error\":\"Trop de demandes d'inscription. Réessayez plus tard.\"}");
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
