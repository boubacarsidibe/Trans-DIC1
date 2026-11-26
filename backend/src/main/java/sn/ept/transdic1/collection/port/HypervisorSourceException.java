package sn.ept.transdic1.collection.port;

/**
 * Échec d'un appel à l'API d'un hyperviseur. La raison distingue une panne (l'hôte ne répond pas :
 * elle peut ouvrir une alerte de collecte) d'une erreur de configuration (jeton, certificat,
 * réponse illisible : elle ne doit pas faire croire que les hôtes sont tombés).
 */
public class HypervisorSourceException extends Exception {
  /** Pourquoi l'appel a échoué. */
  public enum Reason {
    UNREACHABLE,
    TIMEOUT,
    AUTH_REFUSED,
    CERTIFICATE_REFUSED,
    INVALID_RESPONSE,
    NOT_AVAILABLE
  }

  private final Reason reason;
  private final int httpStatus;

  public HypervisorSourceException(Reason reason, String message) {
    this(reason, message, 0);
  }

  /** Échec signalé par une réponse HTTP de l'hyperviseur (et non par le réseau). */
  public HypervisorSourceException(Reason reason, String message, int httpStatus) {
    super(message);
    this.reason = reason;
    this.httpStatus = httpStatus;
  }

  public HypervisorSourceException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
    this.httpStatus = 0;
  }

  public Reason reason() {
    return reason;
  }

  /** Code HTTP de la réponse en échec, ou 0 si l'échec vient du réseau (connexion, délai). */
  public int httpStatus() {
    return httpStatus;
  }

  /** Vrai si l'échec dit que l'hyperviseur est injoignable, et non mal configuré. */
  public boolean meansUnreachable() {
    return reason == Reason.UNREACHABLE || reason == Reason.TIMEOUT;
  }
}
