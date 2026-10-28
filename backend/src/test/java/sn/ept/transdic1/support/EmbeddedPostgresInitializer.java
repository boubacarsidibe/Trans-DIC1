package sn.ept.transdic1.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Démarre un seul vrai PostgreSQL 16 pour toute l'exécution des tests, sans Docker : les binaires
 * natifs sont lancés comme un processus par la JVM (ADR-0003). Flyway applique exactement les
 * migrations de production, y compris les index uniques partiels et la table des métriques
 * partitionnée. Enregistré dans META-INF/spring.factories, il s'applique à tous les @SpringBootTest
 * sans les modifier.
 */
public class EmbeddedPostgresInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {
  private static final EmbeddedPostgres POSTGRES = start();

  private static EmbeddedPostgres start() {
    try {
      EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
      // Le processus est arrêté avec la JVM : un seul serveur partagé par tous les contextes
      // Spring.
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      postgres.close();
                    } catch (IOException ignored) {
                      // Rien à faire à l'arrêt de la JVM.
                    }
                  }));
      return postgres;
    } catch (IOException e) {
      throw new UncheckedIOException("PostgreSQL embarqué impossible à démarrer", e);
    }
  }

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    TestPropertyValues.of(
            "spring.datasource.url=" + POSTGRES.getJdbcUrl("postgres", "postgres"),
            "spring.datasource.username=postgres",
            "spring.datasource.password=")
        .applyTo(context.getEnvironment());
  }
}
