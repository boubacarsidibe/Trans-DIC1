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
 * Limite le nombre de tentatives de connexion, et de renouvellements de jeton, par adresse. La clé
 * est l'adresse de la connexion TCP : derrière un reverse proxy de confiance, activer
 * server.forward-headers-strategy=native pour que ce soit l'adresse réelle du client.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String REFRESH_PATH = "/api/v1/auth/refresh";

  /** Un renouvellement est légitime toutes les quelques minutes : le seuil est plus large. */
  private static final int REFRESH_FACTOR = 3;

  private static final int MAX_TRACKED_ADDRESSES = 10_000;

  private final long attempts;
  private final Duration window;
  private final Map<String, Bucket> buckets =
      new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
          return size() > MAX_TRACKED_ADDRESSES;
        }
      };

  public LoginRateLimitFilter(
      @Value("${security.login.rate-limit.attempts:10}") long attempts,
      @Value("${security.login.rate-limit.window-seconds:60}") long windowSeconds) {
    this.attempts = attempts;
    this.window = Duration.ofSeconds(windowSeconds);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !("POST".equals(request.getMethod())
        && (LOGIN_PATH.equals(path) || REFRESH_PATH.equals(path)));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    long capacity = REFRESH_PATH.equals(path) ? attempts * REFRESH_FACTOR : attempts;
    var probe =
        bucketFor(path + "|" + request.getRemoteAddr(), capacity).tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      chain.doFilter(request, response);
      return;
    }
    long retryAfter =
        Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
    log.warn(
        "security_event action=AUTH_RATE_LIMITED path={} retryAfterSeconds={}", path, retryAfter);
    response.setStatus(429);
    response.setHeader("Retry-After", Long.toString(retryAfter));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write("{\"status\":429,\"error\":\"Trop de tentatives. Réessayez plus tard.\"}");
  }

  private Bucket bucketFor(String key, long capacity) {
    synchronized (buckets) {
      return buckets.computeIfAbsent(
          key,
          k ->
              Bucket.builder()
                  .addLimit(
                      Bandwidth.builder()
                          .capacity(capacity)
                          .refillIntervally(capacity, window)
                          .build())
                  .build());
    }
  }
}
