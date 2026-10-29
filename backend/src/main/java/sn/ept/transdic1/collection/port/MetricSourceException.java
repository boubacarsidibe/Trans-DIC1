package sn.ept.transdic1.collection.port;

/** Échec de lecture d'une source : l'appelant peut réessayer, dans la limite qu'il s'est fixée. */
public class MetricSourceException extends Exception {
  public MetricSourceException(String message) {
    super(message);
  }

  public MetricSourceException(String message, Throwable cause) {
    super(message, cause);
  }
}
