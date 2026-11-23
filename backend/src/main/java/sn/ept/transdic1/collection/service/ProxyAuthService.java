package sn.ept.transdic1.collection.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.collection.dto.ProxyPrincipal;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.repository.CollectionProxyRepository;

/** Authentifie un proxy de collecte par son jeton et note chacune de ses communications. */
@Service
public class ProxyAuthService {
  private final CollectionProxyRepository proxies;
  private final Clock clock;

  public ProxyAuthService(CollectionProxyRepository proxies, Clock clock) {
    this.proxies = proxies;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Optional<ProxyPrincipal> authenticate(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return proxies
        .findByTokenHashAndStatus(AgentAuthService.hash(token), AgentStatus.ACTIVE)
        .map(proxy -> new ProxyPrincipal(proxy.getId(), proxy.getSite().getId()));
  }

  /** Enregistre la dernière communication du proxy et son adresse. */
  @Transactional
  public void recordCommunication(UUID proxyId, String ipAddress) {
    proxies
        .findById(proxyId)
        .ifPresent(
            proxy -> {
              proxy.setLastCommunicationAt(clock.instant());
              proxy.setIpAddress(ipAddress);
              proxies.save(proxy);
            });
  }
}
