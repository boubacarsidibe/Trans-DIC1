package sn.ept.transdic1.collection.port;

import java.util.List;
import sn.ept.transdic1.inventory.domain.HypervisorResourceKind;
import sn.ept.transdic1.inventory.domain.PowerState;

/**
 * Ce qu'un hyperviseur dit de lui-même à un instant : ses hôtes et ses invités. Les charges sont en
 * pourcentage (0 à 100), null quand l'hyperviseur ne les donne pas ; les compteurs réseau sont des
 * octets cumulés depuis le démarrage de l'invité.
 */
public record HypervisorInventory(List<Host> hosts, List<Guest> guests) {

  /**
   * Un hôte physique.
   *
   * @param externalId identifiant stable chez l'hyperviseur (ex. {@code node/pve-01})
   * @param address adresse de gestion connue de l'hyperviseur, ou null
   */
  public record Host(
      String externalId,
      String name,
      PowerState power,
      String address,
      Double cpuPercent,
      Double memoryPercent,
      Double diskPercent,
      Long uptimeSeconds) {}

  /**
   * Une VM ou un conteneur.
   *
   * @param externalId identifiant stable chez l'hyperviseur (ex. {@code qemu/101})
   * @param hostExternalId hôte sur lequel l'invité tourne en ce moment
   * @param template vrai pour un modèle : jamais supervisé
   */
  public record Guest(
      String externalId,
      String name,
      HypervisorResourceKind kind,
      String hostExternalId,
      PowerState power,
      boolean template,
      Double cpuPercent,
      Double memoryPercent,
      Double diskPercent,
      Long netInBytes,
      Long netOutBytes,
      Long uptimeSeconds) {}
}
