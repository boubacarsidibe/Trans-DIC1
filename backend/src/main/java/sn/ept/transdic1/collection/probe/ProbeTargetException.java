package sn.ept.transdic1.collection.probe;

/** Cible de sonde refusée (hôte vide, invalide ou adresse interdite). */
public class ProbeTargetException extends RuntimeException {
  public ProbeTargetException(String message) {
    super(message);
  }
}
