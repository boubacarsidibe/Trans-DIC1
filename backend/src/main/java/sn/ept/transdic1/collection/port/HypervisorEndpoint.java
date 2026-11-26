package sn.ept.transdic1.collection.port;

import java.net.URI;
import java.time.Duration;

/**
 * Où et comment joindre l'API d'un hyperviseur. Le secret du jeton n'est résolu qu'au moment de
 * l'appel (depuis sa référence {@code env:NOM}) et n'apparaît jamais dans {@link #toString()}.
 *
 * @param caCertificatePem autorité de certification interne acceptée, ou null pour les autorités du
 *     système ; la vérification du certificat et du nom d'hôte n'est jamais désactivée
 */
public record HypervisorEndpoint(
    URI apiUrl, String tokenId, String secret, String caCertificatePem, Duration timeout) {

  @Override
  public String toString() {
    return "HypervisorEndpoint[apiUrl=" + apiUrl + ", tokenId=" + tokenId + "]";
  }
}
