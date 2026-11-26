package sn.ept.transdic1.collection.port;

import java.util.List;
import java.util.Optional;
import sn.ept.transdic1.inventory.domain.HypervisorKind;

/**
 * Port de collecte sans agent auprès d'un hyperviseur (ADR 0055) : ce que sait faire n'importe quel
 * connecteur (Proxmox VE, puis VMware vSphere ou Hyper-V), sans rien supposer de son API.
 *
 * <p>Le métier — création des équipements, liens VM → hôte, mesures, alertes — vit dans {@code
 * HypervisorCollectionService} et ne connaît que ce port : ajouter un hyperviseur, c'est écrire une
 * implémentation de plus, sans toucher au reste. Le connecteur ne fait que lire.
 */
public interface HypervisorSource {
  /** Famille d'hyperviseur servie par cette implémentation. */
  HypervisorKind kind();

  /** Faux pour un connecteur seulement esquissé : la plateforme refuse alors de le configurer. */
  default boolean available() {
    return true;
  }

  /** Hôtes et invités (VM, conteneurs) avec leur état et leur charge du moment. */
  HypervisorInventory read(HypervisorEndpoint endpoint) throws HypervisorSourceException;

  /**
   * Adresses d'un invité allumé, données par son agent invité (QEMU Guest Agent sous Proxmox). Vide
   * si l'agent invité est absent ou si le jeton n'a pas le droit de le lire.
   */
  List<String> guestAddresses(HypervisorEndpoint endpoint, HypervisorInventory.Guest guest)
      throws HypervisorSourceException;

  /**
   * Identifiant matériel stable de l'invité (UUID SMBIOS d'une VM), qui distingue une VM recréée
   * reprenant le même numéro. Vide si l'hyperviseur ne le donne pas.
   */
  Optional<String> guestUuid(HypervisorEndpoint endpoint, HypervisorInventory.Guest guest)
      throws HypervisorSourceException;
}
