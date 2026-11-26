package sn.ept.transdic1.collection.hypervisor;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.collection.port.HypervisorEndpoint;
import sn.ept.transdic1.collection.port.HypervisorInventory;
import sn.ept.transdic1.collection.port.HypervisorSource;
import sn.ept.transdic1.collection.port.HypervisorSourceException;
import sn.ept.transdic1.collection.port.HypervisorSourceException.Reason;
import sn.ept.transdic1.inventory.domain.HypervisorKind;

/**
 * Squelette du connecteur VMware vSphere (ADR 0055) : déclaré, pas encore écrit. La plateforme
 * refuse de configurer un connecteur vSphere tant que {@link #available()} est faux.
 *
 * <p>Ce qu'il faudra faire, sans toucher au reste de la plateforme (seul ce fichier change) :
 *
 * <ul>
 *   <li>Session : {@code POST /api/session} sur vCenter avec un compte en lecture seule (rôle «
 *       Read-only » propagé), puis l'en-tête {@code vmware-api-session-id}. Le mot de passe se
 *       range comme le jeton Proxmox, par sa référence {@code env:NOM} (champ {@code secret} de
 *       {@link HypervisorEndpoint}, {@code tokenId} = nom du compte).
 *   <li>Hôtes : {@code GET /api/vcenter/host} (identifiant stable {@code host-NN}, état de
 *       connexion et d'alimentation) ; invités : {@code GET /api/vcenter/vm} (identifiant {@code
 *       vm-NN}, état d'alimentation), l'hôte courant par {@code GET /api/vcenter/vm/{vm}}.
 *   <li>Charge : l'API Automation ne donne pas la charge ; il faut l'API SOAP (PerformanceManager)
 *       ou les statistiques de vCenter. À défaut, ne remonter que l'état d'alimentation.
 *   <li>Adresses : {@code GET /api/vcenter/vm/{vm}/guest/networking/interfaces} (VMware Tools) ;
 *       UUID matériel : {@code instance_uuid} de la VM.
 * </ul>
 *
 * Non écrit faute de savoir si le CRI utilise vSphere : à confirmer avant de l'implémenter.
 */
@Component
public class VsphereHypervisorSource implements HypervisorSource {

  @Override
  public HypervisorKind kind() {
    return HypervisorKind.VSPHERE;
  }

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public HypervisorInventory read(HypervisorEndpoint endpoint) throws HypervisorSourceException {
    throw notAvailable();
  }

  @Override
  public List<String> guestAddresses(HypervisorEndpoint endpoint, HypervisorInventory.Guest guest)
      throws HypervisorSourceException {
    throw notAvailable();
  }

  @Override
  public Optional<String> guestUuid(HypervisorEndpoint endpoint, HypervisorInventory.Guest guest)
      throws HypervisorSourceException {
    throw notAvailable();
  }

  private static HypervisorSourceException notAvailable() {
    return new HypervisorSourceException(
        Reason.NOT_AVAILABLE, "Connecteur VMware vSphere pas encore disponible");
  }
}
