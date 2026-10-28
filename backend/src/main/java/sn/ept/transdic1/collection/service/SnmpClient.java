package sn.ept.transdic1.collection.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import sn.ept.transdic1.inventory.domain.SnmpVersion;

/**
 * Port vers un équipement SNMP : le métier ne dépend pas de la bibliothèque (SNMP4J aujourd'hui,
 * remplaçable sans toucher au reste, ADR-0006).
 */
public interface SnmpClient {

  /** Cible d'une lecture. Les secrets sont déjà résolus ; ils ne sont jamais journalisés. */
  record Target(
      String host,
      int port,
      SnmpVersion version,
      String community,
      String username,
      String authPassphrase,
      String privPassphrase,
      Duration timeout,
      int retries) {

    @Override
    public String toString() {
      return "Target[host=" + host + ", port=" + port + ", version=" + version + "]";
    }
  }

  /**
   * Valeur lue pour un OID : numérique, textuelle, ou absente ({@code number} et {@code text}
   * nuls).
   */
  record Reading(String oid, BigDecimal number, String text) {
    public boolean isNumeric() {
      return number != null;
    }
  }

  /** Lit les OID demandés en une requête GET. */
  List<Reading> get(Target target, List<String> oids) throws SnmpFailure;

  /** Échec de lecture : délai dépassé, erreur SNMP, authentification refusée. */
  class SnmpFailure extends Exception {
    public SnmpFailure(String message) {
      super(message);
    }

    public SnmpFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
