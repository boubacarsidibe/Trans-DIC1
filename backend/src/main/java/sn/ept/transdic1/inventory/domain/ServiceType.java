package sn.ept.transdic1.inventory.domain;

public enum ServiceType {
  TCP_PORT,
  HTTP,
  HTTPS,
  ICMP,
  DNS,
  TLS_CERT,
  OTHER,
  /**
   * Service système suivi par l'agent de l'équipement (nginx, postgresql, Spooler) : son état est
   * la mesure {@code service.up[nom]} (ADR 0056). Jamais sondé depuis le serveur.
   */
  AGENT_SERVICE
}
