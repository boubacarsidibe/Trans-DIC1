package sn.ept.transdic1.collection.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.AgentRegistrationResponse;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionAgent;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/** Enregistre et révoque l'agent Trans-DIC1 d'un équipement (un seul agent actif à la fois). */
@Service
public class AgentRegistrationService {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final EquipmentRepository equipments;
  private final CollectionAgentRepository agents;
  private final AuditService audit;
  private final Clock clock;

  public AgentRegistrationService(
      EquipmentRepository equipments,
      CollectionAgentRepository agents,
      AuditService audit,
      Clock clock) {
    this.equipments = equipments;
    this.agents = agents;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Crée un agent actif et son jeton. Un agent déjà actif sur cet équipement est révoqué : changer
   * de jeton, c'est enregistrer à nouveau.
   */
  @Transactional
  public AgentRegistrationResponse register(UUID equipmentId) {
    var equipment =
        equipments
            .findById(equipmentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement introuvable"));
    var now = clock.instant();
    agents
        .findByEquipmentIdAndStatus(equipmentId, AgentStatus.ACTIVE)
        .ifPresent(
            previous -> {
              previous.setStatus(AgentStatus.REVOKED);
              previous.setRevokedAt(now);
              agents.saveAndFlush(previous);
              audit.record(
                  "AGENT_REVOKED", "EQUIPMENT", equipmentId, "agent=" + previous.getIdentifier());
            });

    String token = newToken();
    String identifier = "agent-" + UUID.randomUUID().toString().substring(0, 8);
    var agent = new CollectionAgent(equipment, identifier, AgentStatus.ACTIVE, now);
    agent.setTokenHash(AgentAuthService.hash(token));
    agents.save(agent);
    audit.record("AGENT_REGISTERED", "EQUIPMENT", equipmentId, "agent=" + identifier);
    return new AgentRegistrationResponse(agent.getId(), identifier, token);
  }

  @Transactional
  public void revoke(UUID equipmentId) {
    var agent =
        agents
            .findByEquipmentIdAndStatus(equipmentId, AgentStatus.ACTIVE)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Aucun agent actif pour cet équipement"));
    agent.setStatus(AgentStatus.REVOKED);
    agent.setRevokedAt(clock.instant());
    agents.save(agent);
    audit.record("AGENT_REVOKED", "EQUIPMENT", equipmentId, "agent=" + agent.getIdentifier());
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "tdic1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
