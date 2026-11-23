package sn.ept.transdic1.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionProxy;

public interface CollectionProxyRepository extends JpaRepository<CollectionProxy, UUID> {

  Optional<CollectionProxy> findBySiteIdAndStatus(UUID siteId, AgentStatus status);

  List<CollectionProxy> findByStatus(AgentStatus status);

  /** Proxy actif présentant ce jeton (par empreinte) : authentification des envois de mesures. */
  Optional<CollectionProxy> findByTokenHashAndStatus(String tokenHash, AgentStatus status);
}
