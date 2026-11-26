package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.collection.hypervisor.ProxmoxHypervisorSource;
import sn.ept.transdic1.collection.port.HypervisorEndpoint;
import sn.ept.transdic1.collection.port.HypervisorInventory;
import sn.ept.transdic1.collection.port.HypervisorInventory.Guest;
import sn.ept.transdic1.collection.port.HypervisorInventory.Host;
import sn.ept.transdic1.collection.port.HypervisorSourceException;
import sn.ept.transdic1.collection.port.HypervisorSourceException.Reason;
import sn.ept.transdic1.collection.service.HypervisorActionService;
import sn.ept.transdic1.collection.service.HypervisorCollectionService;
import sn.ept.transdic1.inventory.domain.HypervisorKind;
import sn.ept.transdic1.inventory.domain.HypervisorResourceKind;
import sn.ept.transdic1.inventory.domain.PowerState;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Redémarrage forcé d'une VM bloquée par l'hyperviseur (ADR 0057) : désactivé par défaut, second
 * jeton distinct, VM seulement, demande motivée puis approbation par un autre administrateur,
 * expiration, refus, échec de l'hyperviseur, audit — l'API Proxmox étant remplacée par un double.
 */
@SpringBootTest(
    properties = {
      "TDIC1_TEST_PVE_SECRET=secret-lecture-jamais-affiche",
      "TDIC1_TEST_PVE_POWER=secret-action-jamais-affiche"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HypervisorVmActionTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String READ_TOKEN = "supervision@pve!trans-dic1";
  private static final String POWER_TOKEN = "supervision@pve!trans-dic1-actions";

  @MockitoBean private ProxmoxHypervisorSource proxmox;
  @Autowired private HypervisorCollectionService collection;
  @Autowired private HypervisorActionService vmActions;
  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site site;
  private String adminJwt;
  private String secondAdminJwt;
  private String viewerJwt;
  private UUID connector;

  @BeforeEach
  void data() throws Exception {
    when(proxmox.kind()).thenReturn(HypervisorKind.PROXMOX);
    when(proxmox.available()).thenReturn(true);
    when(proxmox.guestUuid(any(), any())).thenReturn(Optional.empty());
    when(proxmox.guestAddresses(any(), any())).thenReturn(List.of());
    when(proxmox.resetGuest(any(), anyString(), anyString())).thenReturn("UPID:pve-01:reset");
    when(proxmox.read(any()))
        .thenReturn(
            new HypervisorInventory(
                List.of(
                    new Host("node/pve-01", "pve-01", PowerState.RUNNING, null, 1.0, 1.0, 1.0, 1L)),
                List.of(
                    guest("qemu/101", "web-01", HypervisorResourceKind.VM),
                    guest("lxc/201", "dns-01", HypervisorResourceKind.CONTAINER))));
    users.deleteAll();
    users.save(
        new AppUser("vm-admin", "vm-admin@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    users.save(
        new AppUser("vm-admin2", "vm-admin2@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    users.save(
        new AppUser("vm-viewer", "vm-viewer@example.test", encoder.encode(PASSWORD), Role.VIEWER));
    adminJwt = login("vm-admin");
    secondAdminJwt = login("vm-admin2");
    viewerJwt = login("vm-viewer");
    site = sites.save(new Site("VMA-" + UUID.randomUUID().toString().substring(0, 8), "Salle VM"));
    String body =
        send(
                post("/api/v1/hyperviseurs"),
                adminJwt,
                connectorJson(POWER_TOKEN, "env:TDIC1_TEST_PVE_POWER", true))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.identifiantJetonActions").value(POWER_TOKEN))
            .andExpect(jsonPath("$.referenceSecretActions").value("env:TDIC1_TEST_PVE_POWER"))
            .andExpect(jsonPath("$.actionsVmActives").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("jamais-affiche");
    connector = UUID.fromString(JsonPath.read(body, "$.id"));
    collection.synchronize(connector);
    em.flush();
  }

  private static Guest guest(String id, String name, HypervisorResourceKind kind) {
    return new Guest(
        id, name, kind, "node/pve-01", PowerState.RUNNING, false, 99.0, 50.0, null, 1L, 1L, 60L);
  }

  private String login(String username) throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }

  private String connectorJson(String powerToken, String powerSecret, boolean enabled) {
    return "{\"nom\":\"Cluster VM\",\"type\":\"PROXMOX\",\"urlApi\":\"https://192.0.2.20:8006\","
        + "\"identifiantJeton\":\""
        + READ_TOKEN
        + "\",\"referenceSecret\":\"env:TDIC1_TEST_PVE_SECRET\",\"intervalleSecondes\":300,"
        + "\"siteId\":\""
        + site.getId()
        + "\",\"creerSondesIcmp\":false,\"actif\":true"
        + (powerToken == null ? "" : ",\"identifiantJetonActions\":\"" + powerToken + "\"")
        + (powerSecret == null ? "" : ",\"referenceSecretActions\":\"" + powerSecret + "\"")
        + ",\"actionsVmActives\":"
        + enabled
        + "}";
  }

  private ResultActions send(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      String jwt,
      String json)
      throws Exception {
    return mockMvc.perform(
        request
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private UUID resource(String name) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT id FROM hypervisor_resource WHERE connector_id = ? AND name = ?",
        UUID.class,
        connector,
        name);
  }

  private ResultActions requestReset(String resourceName, String jwt) throws Exception {
    return send(
        post(
            "/api/v1/hyperviseurs/"
                + connector
                + "/ressources/"
                + resource(resourceName)
                + "/redemarrage"),
        jwt,
        "{\"motif\":\"VM figée, console muette depuis 20 minutes\"}");
  }

  private String requested(String resourceName) throws Exception {
    String body =
        requestReset(resourceName, adminJwt)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.etat").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.action").value("RESET"))
            .andExpect(jsonPath("$.demandePar").value("vm-admin"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private long audits(String action) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_entry WHERE action = ?", Long.class, action);
  }

  @Test
  @DisplayName(
      "Demande puis approbation par un autre administrateur : l'ordre part, avec le second jeton")
  void requestThenApprovalByAnotherAdmin() throws Exception {
    String id = requested("web-01");
    // Rien ne part à la demande.
    verify(proxmox, never()).resetGuest(any(), anyString(), anyString());

    // Le demandeur ne s'approuve pas lui-même.
    send(post("/api/v1/hyperviseurs/actions-vm/" + id + "/approbation"), adminJwt, "")
        .andExpect(status().isConflict());
    verify(proxmox, never()).resetGuest(any(), anyString(), anyString());

    send(post("/api/v1/hyperviseurs/actions-vm/" + id + "/approbation"), secondAdminJwt, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.etat").value("SUCCEEDED"))
        .andExpect(jsonPath("$.decidePar").value("vm-admin2"))
        .andExpect(
            jsonPath("$.resultat")
                .value("Ordre accepté par l'hyperviseur (tâche UPID:pve-01:reset)"));

    ArgumentCaptor<HypervisorEndpoint> endpoint = ArgumentCaptor.forClass(HypervisorEndpoint.class);
    verify(proxmox).resetGuest(endpoint.capture(), eq("qemu/101"), eq("node/pve-01"));
    // Le jeton d'action, jamais celui de lecture.
    assertThat(endpoint.getValue().tokenId()).isEqualTo(POWER_TOKEN);
    assertThat(endpoint.getValue().secret()).isEqualTo("secret-action-jamais-affiche");
    assertThat(endpoint.getValue().toString()).doesNotContain("jamais-affiche");

    // Une seule fois : la demande décidée n'attend plus rien.
    send(post("/api/v1/hyperviseurs/actions-vm/" + id + "/approbation"), secondAdminJwt, "")
        .andExpect(status().isConflict());
    assertThat(audits("HYPERVISOR_VM_RESET_REQUESTED")).isEqualTo(1);
    assertThat(audits("HYPERVISOR_VM_RESET_SUCCEEDED")).isEqualTo(1);
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE details_sanitized LIKE '%jamais-affiche%'",
                Long.class))
        .isZero();

    mockMvc
        .perform(
            get("/api/v1/hyperviseurs/" + connector + "/actions-vm")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].etat").value("SUCCEEDED"))
        .andExpect(jsonPath("$[0].ressourceNom").value("web-01"));
  }

  @Test
  @DisplayName("Désactivé par défaut : sans second jeton ni activation, aucune demande possible")
  void disabledByDefault() throws Exception {
    String body =
        send(
                post("/api/v1/hyperviseurs"),
                adminJwt,
                connectorJson(null, null, false).replace("192.0.2.20", "192.0.2.21"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.actionsVmActives").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    connector = UUID.fromString(JsonPath.read(body, "$.id"));
    collection.synchronize(connector);

    requestReset("web-01", adminJwt)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("désactivées")));

    // Jeton présent mais interrupteur coupé : toujours non.
    send(
            put("/api/v1/hyperviseurs/" + connector),
            adminJwt,
            connectorJson(POWER_TOKEN, "env:TDIC1_TEST_PVE_POWER", false)
                .replace("192.0.2.20", "192.0.2.21"))
        .andExpect(status().isOk());
    requestReset("web-01", adminJwt).andExpect(status().isConflict());
  }

  @Test
  @DisplayName("Second jeton : distinct du jeton de lecture, complet, et exigé pour activer")
  void powerTokenValidation() throws Exception {
    // Le jeton de lecture ne peut pas servir à agir.
    send(
            put("/api/v1/hyperviseurs/" + connector),
            adminJwt,
            connectorJson(READ_TOKEN, "env:TDIC1_TEST_PVE_POWER", true))
        .andExpect(status().isBadRequest());
    send(
            put("/api/v1/hyperviseurs/" + connector),
            adminJwt,
            connectorJson(POWER_TOKEN, "env:TDIC1_TEST_PVE_SECRET", true))
        .andExpect(status().isBadRequest());
    // Incomplet.
    send(put("/api/v1/hyperviseurs/" + connector), adminJwt, connectorJson(POWER_TOKEN, null, true))
        .andExpect(status().isBadRequest());
    // Activé sans jeton.
    send(put("/api/v1/hyperviseurs/" + connector), adminJwt, connectorJson(null, null, true))
        .andExpect(status().isBadRequest());
    // Un secret en clair au lieu d'une référence.
    send(
            put("/api/v1/hyperviseurs/" + connector),
            adminJwt,
            connectorJson(POWER_TOKEN, "secret-en-clair", true))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Une VM seulement : ni conteneur ni hôte ; une seule demande en attente par VM")
  void onlyVirtualMachines() throws Exception {
    requestReset("dns-01", adminJwt).andExpect(status().isConflict());
    requestReset("pve-01", adminJwt).andExpect(status().isConflict());
    requested("web-01");
    requestReset("web-01", adminJwt).andExpect(status().isConflict());
    // Motif obligatoire.
    send(
            post(
                "/api/v1/hyperviseurs/"
                    + connector
                    + "/ressources/"
                    + resource("web-01")
                    + "/redemarrage"),
            adminJwt,
            "{\"motif\":\"\"}")
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Refus, expiration et échec de l'hyperviseur : tracés, rien ne part après coup")
  void rejectionExpiryAndFailure() throws Exception {
    String rejected = requested("web-01");
    send(post("/api/v1/hyperviseurs/actions-vm/" + rejected + "/refus"), secondAdminJwt, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.etat").value("REJECTED"));
    send(post("/api/v1/hyperviseurs/actions-vm/" + rejected + "/approbation"), secondAdminJwt, "")
        .andExpect(status().isConflict());

    String expired = requested("web-01");
    assertThat(vmActions.expire(Instant.now().plus(2, ChronoUnit.HOURS))).isEqualTo(1);
    send(post("/api/v1/hyperviseurs/actions-vm/" + expired + "/approbation"), secondAdminJwt, "")
        .andExpect(status().isConflict());
    verify(proxmox, never()).resetGuest(any(), anyString(), anyString());

    doThrow(
            new HypervisorSourceException(
                Reason.AUTH_REFUSED, "Droits insuffisants (HTTP 403)", 403))
        .when(proxmox)
        .resetGuest(any(), anyString(), anyString());
    String failed = requested("web-01");
    send(post("/api/v1/hyperviseurs/actions-vm/" + failed + "/approbation"), secondAdminJwt, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.etat").value("FAILED"))
        .andExpect(jsonPath("$.resultat").value("Ordre refusé : Droits insuffisants (HTTP 403)"));
    assertThat(audits("HYPERVISOR_VM_RESET_REJECTED")).isEqualTo(1);
    assertThat(audits("HYPERVISOR_VM_RESET_EXPIRED")).isEqualTo(1);
    assertThat(audits("HYPERVISOR_VM_RESET_FAILED")).isEqualTo(1);
  }

  @Test
  @DisplayName("Réservé aux administrateurs")
  void adminsOnly() throws Exception {
    requestReset("web-01", viewerJwt).andExpect(status().isForbidden());
    String id = requested("web-01");
    send(post("/api/v1/hyperviseurs/actions-vm/" + id + "/approbation"), viewerJwt, "")
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/hyperviseurs/" + connector + "/actions-vm")
                .header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isForbidden());
  }
}
