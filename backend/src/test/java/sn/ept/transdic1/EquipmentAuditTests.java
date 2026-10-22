package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.audit.domain.AuditEntry;
import sn.ept.transdic1.audit.repository.AuditEntryRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Les mutations d'inventaire sont auditées : auteur, cible, correlation ID, sans donnée sensible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EquipmentAuditTests {
  private static final String SITE_ID = "10000000-0000-0000-0000-000000000001";
  private static final String CORRELATION = "test-correlation-42";

  @Autowired private MockMvc mockMvc;
  @Autowired private AuditEntryRepository audits;
  @Autowired private UserRepository users;

  @BeforeEach
  void author() {
    if (users.findByUsername("audit-admin").isEmpty()) {
      users.save(new AppUser("audit-admin", "audit-admin@example.test", "hash", Role.ADMIN));
    }
  }

  private static String body(String code, String name, String address) {
    return """
        {"inventoryCode":"%s","name":"%s","managementAddress":"%s",
         "type":"SERVER","status":"UP","siteId":"%s"}
        """
        .formatted(code, name, address, SITE_ID);
  }

  private String createEquipment(String code, String address) throws Exception {
    String json =
        mockMvc
            .perform(
                post("/api/v1/equipements")
                    .header("X-Correlation-ID", CORRELATION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(code, "Equipement audité", address)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  private List<AuditEntry> entriesFor(String id) {
    return audits
        .findByTargetTypeAndTargetIdOrderByPerformedAtDesc(
            "EQUIPMENT", UUID.fromString(id), org.springframework.data.domain.PageRequest.of(0, 20))
        .getContent();
  }

  @Test
  @WithMockUser(username = "audit-admin", roles = "ADMIN")
  void creationIsAuditedWithAuthorTargetAndCorrelationId() throws Exception {
    String id = createEquipment("AUDIT-001", "192.0.2.201");

    var entries = entriesFor(id);
    assertThat(entries).hasSize(1);
    var entry = entries.get(0);
    assertThat(entry.getAction()).isEqualTo("EQUIPMENT_CREATED");
    assertThat(entry.getUsername()).isEqualTo("audit-admin");
    assertThat(entry.getAuthor()).isNotNull();
    assertThat(entry.getAuthor().getUsername()).isEqualTo("audit-admin");
    assertThat(entry.getCorrelationId()).isEqualTo(CORRELATION);
    assertThat(entry.getDetailsSanitized()).contains("AUDIT-001");
    assertThat(entry.getPerformedAt()).isNotNull();
  }

  @Test
  @WithMockUser(username = "audit-admin", roles = "ADMIN")
  void updateAndDeletionAreAudited() throws Exception {
    String id = createEquipment("AUDIT-002", "192.0.2.202");

    mockMvc
        .perform(
            put("/api/v1/equipements/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("AUDIT-002", "Renommé", "192.0.2.202")))
        .andExpect(status().isOk());
    mockMvc.perform(delete("/api/v1/equipements/" + id)).andExpect(status().isNoContent());

    assertThat(entriesFor(id))
        .extracting(AuditEntry::getAction)
        .containsExactlyInAnyOrder("EQUIPMENT_CREATED", "EQUIPMENT_UPDATED", "EQUIPMENT_DELETED");
  }

  @Test
  @WithMockUser(username = "audit-admin", roles = "ADMIN")
  void aRejectedMutationLeavesNoAuditEntry() throws Exception {
    createEquipment("AUDIT-003", "192.0.2.203");
    long before = audits.count();

    mockMvc
        .perform(
            post("/api/v1/equipements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("AUDIT-003", "Doublon", "192.0.2.203")))
        .andExpect(status().isConflict());

    assertThat(audits.count()).isEqualTo(before);
  }

  @Test
  @WithMockUser(username = "audit-viewer", roles = "VIEWER")
  void aForbiddenMutationIsNotAuditedAndReadsNeverAre() throws Exception {
    long before = audits.count();

    mockMvc
        .perform(
            post("/api/v1/equipements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("AUDIT-004", "Interdit", "192.0.2.204")))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/equipements")).andExpect(status().isOk());

    assertThat(audits.count()).isEqualTo(before);
  }
}
