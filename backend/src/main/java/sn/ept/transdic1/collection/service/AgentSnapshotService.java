package sn.ept.transdic1.collection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.collection.dto.AgentSnapshotDto;
import sn.ept.transdic1.collection.dto.AgentSnapshotResponse;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.AgentSnapshot;
import sn.ept.transdic1.inventory.repository.AgentSnapshotRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/** Reçoit et restitue le dernier inventaire d'un équipement : une seule ligne, sans historique. */
@Service
public class AgentSnapshotService {
  private final AgentSnapshotRepository snapshots;
  private final EquipmentRepository equipments;
  private final ObjectMapper json;
  private final Clock clock;
  private final SiteScope scope;

  public AgentSnapshotService(
      AgentSnapshotRepository snapshots,
      EquipmentRepository equipments,
      ObjectMapper json,
      Clock clock,
      SiteScope scope) {
    this.snapshots = snapshots;
    this.equipments = equipments;
    this.json = json;
    this.clock = clock;
    this.scope = scope;
  }

  @Transactional
  public void store(UUID equipmentId, AgentSnapshotDto snapshot) {
    String payload;
    try {
      payload = json.writeValueAsString(snapshot);
    } catch (JsonProcessingException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Instantané illisible");
    }
    var now = clock.instant();
    snapshots
        .findById(equipmentId)
        .ifPresentOrElse(
            found -> found.replace(payload, now),
            () -> snapshots.save(new AgentSnapshot(equipmentId, payload, now)));
  }

  @Transactional(readOnly = true)
  public AgentSnapshotResponse read(UUID equipmentId) {
    var equipment =
        equipments
            .findById(equipmentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement inconnu"));
    scope.requireVisible(equipment.getSite().getId(), "Équipement inconnu");
    var found =
        snapshots
            .findById(equipmentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucun inventaire reçu"));
    try {
      return new AgentSnapshotResponse(
          found.getReceivedAt(), json.readValue(found.getPayload(), AgentSnapshotDto.class));
    } catch (JsonProcessingException error) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Inventaire illisible");
    }
  }
}
