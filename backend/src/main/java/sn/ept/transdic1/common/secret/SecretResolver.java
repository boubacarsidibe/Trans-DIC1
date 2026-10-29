package sn.ept.transdic1.common.secret;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Résout une référence de secret en sa valeur. La base ne contient que la référence (par exemple
 * {@code env:SNMP_CAMPUS_COMMUNITY}), jamais le secret : celui-ci vit dans l'environnement du
 * serveur ou dans le gestionnaire de secrets de la cible. Seules les références {@code env:} sont
 * prises en charge pour l'instant.
 */
@Component
public class SecretResolver {
  private static final String ENV_PREFIX = "env:";

  private final Environment environment;

  public SecretResolver(Environment environment) {
    this.environment = environment;
  }

  /** Vrai si la référence est d'une forme prise en charge. */
  public static boolean isSupported(String reference) {
    return reference != null
        && reference.startsWith(ENV_PREFIX)
        && reference.substring(ENV_PREFIX.length()).matches("[A-Za-z_][A-Za-z0-9_]{0,63}");
  }

  /**
   * @throws IllegalStateException si la référence est invalide ou son secret introuvable ; le
   *     message cite la référence, jamais une valeur.
   */
  public String resolve(String reference) {
    if (!isSupported(reference)) {
      throw new IllegalStateException("Référence de secret non prise en charge : " + reference);
    }
    String value = environment.getProperty(reference.substring(ENV_PREFIX.length()));
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Secret introuvable pour la référence " + reference);
    }
    return value;
  }
}
