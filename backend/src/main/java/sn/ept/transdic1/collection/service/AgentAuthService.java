package sn.ept.transdic1.collection.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.collection.dto.AgentPrincipal;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;

/** Authentifie un agent par son jeton et note chacune de ses communications. */
@Service
public class AgentAuthService {
  private final CollectionAgentRepository agents;
  private final Clock clock;

  public AgentAuthService(CollectionAgentRepository agents, Clock clock) {
    this.agents = agents;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Optional<AgentPrincipal> authenticate(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return agents
        .findByTokenHashAndStatus(hash(token), AgentStatus.ACTIVE)
        .map(agent -> new AgentPrincipal(agent.getId(), agent.getEquipment().getId()));
  }

  /** Enregistre la dernière communication de l'agent et son adresse. */
  @Transactional
  public void recordCommunication(UUID agentId, String ipAddress) {
    agents
        .findById(agentId)
        .ifPresent(
            agent -> {
              agent.setLastCommunicationAt(clock.instant());
              agent.setIpAddress(ipAddress);
              agents.save(agent);
            });
  }

  /** Empreinte stockée à la place du jeton : une fuite de la base ne donne aucun accès. */
  static String hash(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
