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
 * Squelette du connecteur Microsoft Hyper-V (ADR 0055) : déclaré, pas encore écrit. La plateforme
 * refuse de configurer un connecteur Hyper-V tant que {@link #available()} est faux.
 *
 * <p>Hyper-V n'a pas d'API REST. Deux voies, à choisir avec le CRI :
 *
 * <ul>
 *   <li><b>WinRM et WMI</b> ({@code root\virtualization\v2}) sur chaque hôte, ou sur le cluster de
 *       basculement : classes {@code Msvm_ComputerSystem} (identifiant stable : {@code Name}, un
 *       GUID ; état : {@code EnabledState}), {@code Msvm_SummaryInformation} (charge processeur,
 *       mémoire), {@code Msvm_GuestNetworkAdapterConfiguration} (adresses, par les services
 *       d'intégration). Compte membre du groupe local « Hyper-V Administrators » en lecture, secret
 *       par référence {@code env:NOM}.
 *   <li><b>System Center Virtual Machine Manager</b>, s'il est en place : une seule source pour
 *       tous les hôtes, par son API.
 * </ul>
 *
 * Aucune commande arbitraire : le connecteur n'enverrait que des requêtes WMI en lecture, écrites
 * ici. Non écrit faute de savoir si le CRI utilise Hyper-V : à confirmer avant de l'implémenter.
 */
@Component
public class HyperVHypervisorSource implements HypervisorSource {

  @Override
  public HypervisorKind kind() {
    return HypervisorKind.HYPERV;
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
        Reason.NOT_AVAILABLE, "Connecteur Microsoft Hyper-V pas encore disponible");
  }
}
