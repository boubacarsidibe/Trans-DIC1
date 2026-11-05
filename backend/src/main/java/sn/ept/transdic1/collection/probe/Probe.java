package sn.ept.transdic1.collection.probe;

import sn.ept.transdic1.inventory.domain.ServiceType;

/** Une sonde sans agent : teste un service à distance depuis le serveur. */
public interface Probe {
  /** Type de service que cette sonde sait tester. */
  ServiceType type();

  /** Exécute la sonde ; ne lève jamais d'exception, l'échec est porté par le résultat. */
  ProbeResult run(ProbeRequest request);
}
