package sn.ept.transdic1.collection.probe;

import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sn.ept.transdic1.inventory.domain.ServiceType;

/** Déclare les sondes HTTP et HTTPS (contexte TLS par défaut de la JVM, chaîne validée). */
@Configuration
public class HttpProbeConfiguration {
  @Bean
  public HttpProbe httpProbe() {
    return new HttpProbe(ServiceType.HTTP, defaultContext());
  }

  @Bean
  public HttpProbe httpsProbe() {
    return new HttpProbe(ServiceType.HTTPS, defaultContext());
  }

  private static SSLContext defaultContext() {
    try {
      return SSLContext.getDefault();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Contexte TLS par défaut indisponible", e);
    }
  }
}
