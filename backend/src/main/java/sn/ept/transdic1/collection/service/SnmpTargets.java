package sn.ept.transdic1.collection.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.collection.service.SnmpClient.Target;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.inventory.domain.CollectionConfiguration;
import sn.ept.transdic1.inventory.domain.SnmpVersion;

/**
 * Construit la cible SNMP d'une configuration de collecte : version, port, délais, et secrets
 * résolus au dernier moment (la base ne garde qu'une référence, jamais la valeur).
 */
@Component
public class SnmpTargets {
  private final SecretResolver secrets;
  private final int defaultPort;

  public SnmpTargets(
      SecretResolver secrets, @Value("${collection.snmp.default-port:161}") int defaultPort) {
    this.secrets = secrets;
    this.defaultPort = defaultPort;
  }

  public Target of(CollectionConfiguration configuration, String host) {
    int retries = configuration.getRetryCount() == null ? 0 : configuration.getRetryCount();
    int port =
        configuration.getSnmpPort() == null ? defaultPort : configuration.getSnmpPort().intValue();
    if (configuration.getSnmpVersion() == SnmpVersion.V3) {
      String secret = secrets.resolve(configuration.getSecretReference());
      String[] parts = secret.split("\\|", 2);
      return new Target(
          host,
          port,
          SnmpVersion.V3,
          null,
          configuration.getSecurityIdentity(),
          parts[0],
          parts.length > 1 && !parts[1].isBlank() ? parts[1] : null,
          configuration.getTimeout(),
          retries);
    }
    return new Target(
        host,
        port,
        SnmpVersion.V2C,
        secrets.resolve(configuration.getCommunityReference()),
        null,
        null,
        null,
        configuration.getTimeout(),
        retries);
  }
}
