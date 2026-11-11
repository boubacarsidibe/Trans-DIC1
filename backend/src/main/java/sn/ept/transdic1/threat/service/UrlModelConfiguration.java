package sn.ept.transdic1.threat.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Choix du modèle appris au démarrage : celui que l'exploitation a réappris sur son propre trafic
 * si elle en a désigné un, sinon celui livré avec l'application, et rien du tout si le modèle est
 * coupé.
 */
@Configuration
public class UrlModelConfiguration {
  private static final Logger LOG = LoggerFactory.getLogger(UrlModelConfiguration.class);

  @Bean
  public UrlModel urlModel(
      @Value("${threat.model.enabled:true}") boolean enabled,
      @Value("${threat.model.file:}") String file) {
    if (!enabled) {
      LOG.info("Modèle appris de sécurité coupé par configuration");
      return UrlModel.disabled();
    }
    if (!file.isBlank()) {
      Path path = Path.of(file);
      if (Files.isReadable(path)) {
        LOG.info("Modèle appris de sécurité chargé depuis {}", path);
        return UrlModel.fromFile(path);
      }
      // Fichier annoncé mais absent : on le dit et on retombe sur le modèle livré, plutôt que
      // d'empêcher le démarrage de toute la supervision pour un chemin mal écrit.
      LOG.warn("Modèle appris de sécurité introuvable ({}) : modèle livré employé", path);
    }
    return UrlModel.fromResource();
  }
}
