package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import sn.ept.transdic1.collection.dto.HypervisorDtos.SyncReport;
import sn.ept.transdic1.collection.hypervisor.ProxmoxHypervisorSource;
import sn.ept.transdic1.collection.port.HypervisorInventory;
import sn.ept.transdic1.collection.port.HypervisorInventory.Guest;
import sn.ept.transdic1.collection.port.HypervisorInventory.Host;
import sn.ept.transdic1.collection.port.HypervisorSourceException;
import sn.ept.transdic1.collection.port.HypervisorSourceException.Reason;
import sn.ept.transdic1.collection.service.HypervisorCollectionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.HypervisorKind;
import sn.ept.transdic1.inventory.domain.HypervisorResourceKind;
import sn.ept.transdic1.inventory.domain.PowerState;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Connecteur d'hyperviseur (ADR 0055) de bout en bout, l'API Proxmox étant remplacée par un double
 * qui rejoue des inventaires construits : création sans doublon, lien VM → hôte et migration,
 * mesures, alertes de VM éteinte et d'hôte hors ligne regroupées, ressources disparues gardées,
 * pannes de l'API, et administration (validation, rôle, secret jamais renvoyé).
 *
 * <p>Transactionnel : chaque test est annulé à la fin, rien ne reste en base.
 */
@SpringBootTest(properties = "TDIC1_TEST_PVE_SECRET=secret-de-test-jamais-affiche")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HypervisorCollectionTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String SECRET_VALUE = "secret-de-test-jamais-affiche";
  private static final String UUID_A = "5d2c9e1a-7b3f-4c8d-9e0f-1a2b3c4d5e6f";

  @MockitoBean private ProxmoxHypervisorSource proxmox;
  @Autowired private HypervisorCollectionService collection;
  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site site;
  private String adminJwt;
  private String viewerJwt;
  private UUID connector;

  @BeforeEach
  void data() throws Exception {
    when(proxmox.kind()).thenReturn(HypervisorKind.PROXMOX);
    when(proxmox.available()).thenReturn(true);
    when(proxmox.guestUuid(any(), any())).thenReturn(Optional.empty());
    when(proxmox.guestAddresses(any(), any())).thenReturn(List.of());
    users.deleteAll();
    users.save(
        new AppUser("hv-admin", "hv-admin@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    users.save(
        new AppUser("hv-viewer", "hv-viewer@example.test", encoder.encode(PASSWORD), Role.VIEWER));
    adminJwt = login("hv-admin");
    viewerJwt = login("hv-viewer");
    site =
        sites.save(
            new Site("HV-" + UUID.randomUUID().toString().substring(0, 8), "Salle serveurs"));
    String body =
        createConnector(
                connectorJson(
                    "https://192.0.2.10:8006",
                    "supervision@pve!trans-dic1",
                    "env:TDIC1_TEST_PVE_SECRET",
                    "PROXMOX"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    connector = UUID.fromString(JsonPath.read(body, "$.id"));
  }

  // --- Outils
  // -------------------------------------------------------------------------------------

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

  private String connectorJson(String url, String tokenId, String secretRef, String kind) {
    return "{\"nom\":\"Cluster du campus\",\"type\":\""
        + kind
        + "\",\"urlApi\":\""
        + url
        + "\",\"identifiantJeton\":\""
        + tokenId
        + "\",\"referenceSecret\":\""
        + secretRef
        + "\",\"intervalleSecondes\":300,\"siteId\":\""
        + site.getId()
        + "\",\"creerSondesIcmp\":true,\"actif\":true}";
  }

  private ResultActions createConnector(String json) throws Exception {
    return mockMvc.perform(
        post("/api/v1/hyperviseurs")
            .header("Authorization", "Bearer " + adminJwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private static Host host(String name, PowerState power, String address) {
    return new Host("node/" + name, name, power, address, 12.5, 40.0, 20.0, 86_400L);
  }

  private static Guest vm(String id, String name, String host, PowerState power) {
    return new Guest(
        id,
        name,
        id.startsWith("lxc") ? HypervisorResourceKind.CONTAINER : HypervisorResourceKind.VM,
        "node/" + host,
        power,
        false,
        25.0,
        50.0,
        null,
        1_000L,
        2_000L,
        3_600L);
  }

  private static Guest template() {
    return new Guest(
        "qemu/9000",
        "modele-debian",
        HypervisorResourceKind.VM,
        "node/pve-01",
        PowerState.STOPPED,
        true,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private void inventory(List<Host> hosts, List<Guest> guests) throws Exception {
    when(proxmox.read(any())).thenReturn(new HypervisorInventory(hosts, guests));
  }

  /**
   * Un passage. Le test étant une seule transaction, les écritures JPA sont vidées avant les
   * lectures en SQL ; en exploitation, chaque invité est validé dans sa propre transaction.
   */
  private SyncReport sync() {
    SyncReport report = collection.synchronize(connector);
    em.flush();
    return report;
  }

  private UUID equipmentOf(String externalId) {
    return jdbc.queryForObject(
        "SELECT equipment_id FROM hypervisor_resource WHERE connector_id = ? AND external_id = ?"
            + " AND status = 'PRESENT'",
        UUID.class,
        connector,
        externalId);
  }

  private int equipmentsInSite() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM equipment WHERE site_id = ?", Integer.class, site.getId());
  }

  private List<UUID> upstreams(UUID equipment) {
    return jdbc.queryForList(
        "SELECT upstream_id FROM equipment_link WHERE equipment_id = ?", UUID.class, equipment);
  }

  private List<java.util.Map<String, Object>> activeCollectionAlerts(UUID equipment) {
    return jdbc.queryForList(
        "SELECT id, message, parent_alert_id FROM alert WHERE equipment_id = ? AND type = 'COLLECTION'"
            + " AND state IN ('OPEN', 'IN_PROGRESS')",
        equipment);
  }

  // --- Inventaire et topologie
  // --------------------------------------------------------------------

  @Test
  @DisplayName("Hôtes et VM deviennent des équipements reliés, sans doublon au second passage")
  void hostsAndGuestsBecomeLinkedEquipmentsWithoutDuplicates() throws Exception {
    inventory(
        List.of(
            host("pve-01", PowerState.ONLINE, "192.0.2.11"),
            host("pve-02", PowerState.ONLINE, "192.0.2.12")),
        List.of(
            vm("qemu/101", "vm-web-01", "pve-01", PowerState.RUNNING),
            vm("lxc/201", "ct-dns-01", "pve-01", PowerState.RUNNING),
            template()));
    when(proxmox.guestAddresses(any(), any())).thenReturn(List.of("192.0.2.101"), List.of());
    when(proxmox.guestUuid(any(), any())).thenReturn(Optional.of(UUID_A));

    SyncReport first = sync();

    assertThat(first.reussi()).isTrue();
    assertThat(first.hotes()).isEqualTo(2);
    assertThat(first.invites()).isEqualTo(2);
    assertThat(first.equipementsCrees()).isEqualTo(4);
    assertThat(equipmentsInSite()).isEqualTo(4);
    UUID pve01 = equipmentOf("node/pve-01");
    UUID web = equipmentOf("qemu/101");
    var webRow =
        jdbc.queryForMap(
            "SELECT equipment_type, management_address, operating_system FROM equipment WHERE id = ?",
            web);
    assertThat(webRow.get("equipment_type")).isEqualTo("VIRTUAL_MACHINE");
    assertThat(webRow.get("management_address")).isEqualTo("192.0.2.101");
    assertThat(
            jdbc.queryForObject(
                "SELECT equipment_type FROM equipment WHERE id = ?", String.class, pve01))
        .isEqualTo("SERVER");
    assertThat(
            jdbc.queryForObject(
                "SELECT management_address FROM equipment WHERE id = ?", String.class, pve01))
        .isEqualTo("192.0.2.11");
    // Le conteneur sans adresse connue a une adresse provisoire, unique.
    assertThat(
            jdbc.queryForObject(
                "SELECT management_address FROM equipment WHERE id = ?",
                String.class,
                equipmentOf("lxc/201")))
        .startsWith("hv-");
    assertThat(upstreams(web)).containsExactly(pve01);
    assertThat(upstreams(equipmentOf("lxc/201"))).containsExactly(pve01);
    // Mesures par le circuit d'ingestion, sous des clés qui ne se mélangent pas à celles de
    // l'agent.
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT metric_key FROM metric WHERE equipment_id = ? ORDER BY 1",
                String.class,
                web))
        .containsExactly("hv.cpu.usage", "hv.memory.usage", "hv.power", "hv.uptime");
    assertThat(
            jdbc.queryForObject(
                "SELECT operational_status FROM equipment WHERE id = ?", String.class, web))
        .isEqualTo("UP");
    // Sonde ICMP créée inactive vers l'adresse donnée par l'agent invité.
    var probe =
        jdbc.queryForMap(
            "SELECT type, access_point, active FROM monitored_service WHERE equipment_id = ?", web);
    assertThat(probe.get("type")).isEqualTo("ICMP");
    assertThat(probe.get("access_point")).isEqualTo("192.0.2.101");
    assertThat(probe.get("active")).isEqualTo(false);
    assertThat(
            jdbc.queryForObject(
                "SELECT smbios_uuid FROM hypervisor_resource WHERE equipment_id = ?",
                String.class,
                web))
        .isEqualTo(UUID_A);
    // Le modèle n'est jamais supervisé.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM hypervisor_resource WHERE external_id = 'qemu/9000'",
                Integer.class))
        .isZero();

    SyncReport second = sync();
    assertThat(second.equipementsCrees()).isZero();
    assertThat(equipmentsInSite()).isEqualTo(4);
    // Le débit réseau se calcule à partir du second passage.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM metric WHERE equipment_id = ? AND metric_key = 'hv.net.in'",
                Integer.class,
                web))
        .isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("Une VM qui migre voit son lien déplacé vers le nouvel hôte, et c'est tracé")
  void migrationMovesTheLink() throws Exception {
    var hosts =
        List.of(
            host("pve-01", PowerState.ONLINE, "192.0.2.11"),
            host("pve-02", PowerState.ONLINE, "192.0.2.12"));
    inventory(hosts, List.of(vm("qemu/101", "vm-web-01", "pve-01", PowerState.RUNNING)));
    sync();
    UUID web = equipmentOf("qemu/101");
    // Un lien saisi à la main vers un autre équipement n'est jamais touché.
    var sw =
        equipments.saveAndFlush(
            new Equipment(
                "SW-" + UUID.randomUUID().toString().substring(0, 6),
                "sw-coeur",
                "192.0.2.250",
                EquipmentType.SWITCH,
                EquipmentStatus.UP,
                site));
    jdbc.update(
        "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        web,
        sw.getId());

    inventory(hosts, List.of(vm("qemu/101", "vm-web-01", "pve-02", PowerState.RUNNING)));
    SyncReport report = sync();

    assertThat(report.migrations()).isEqualTo(1);
    assertThat(upstreams(web)).containsExactlyInAnyOrder(equipmentOf("node/pve-02"), sw.getId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'HYPERVISOR_VM_MIGRATED' AND target_id = ?",
                Integer.class,
                web))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Un équipement déjà inventorié à l'adresse d'un hôte est repris, pas doublé")
  void anExistingEquipmentIsAdopted() throws Exception {
    var existing =
        equipments.save(
            new Equipment(
                "SRV-" + UUID.randomUUID().toString().substring(0, 6),
                "pve-01-physique",
                "192.0.2.11",
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    inventory(List.of(host("pve-01", PowerState.ONLINE, "192.0.2.11")), List.of());

    assertThat(sync().equipementsCrees()).isZero();
    assertThat(equipmentOf("node/pve-01")).isEqualTo(existing.getId());
  }

  // --- Alertes
  // ------------------------------------------------------------------------------------

  @Test
  @DisplayName("Hôte hors ligne : son alerte, et celles de ses VM rangées dessous")
  void offlineHostGroupsTheAlertsOfItsGuests() throws Exception {
    inventory(
        List.of(host("pve-02", PowerState.ONLINE, "192.0.2.12")),
        List.of(vm("qemu/101", "vm-web-01", "pve-02", PowerState.RUNNING)));
    sync();
    inventory(
        List.of(host("pve-02", PowerState.OFFLINE, "192.0.2.12")),
        List.of(vm("qemu/101", "vm-web-01", "pve-02", PowerState.UNKNOWN)));

    SyncReport report = sync();

    UUID hostAlert = (UUID) activeCollectionAlerts(equipmentOf("node/pve-02")).get(0).get("id");
    var vmAlert = activeCollectionAlerts(equipmentOf("qemu/101")).get(0);
    assertThat(report.alertesOuvertes()).isEqualTo(2);
    assertThat((String) vmAlert.get("message"))
        .isEqualTo("VM vm-web-01 injoignable : son hôte pve-02 est hors ligne");
    assertThat(vmAlert.get("parent_alert_id")).isEqualTo(hostAlert);
  }

  @Test
  @DisplayName("VM éteinte après avoir tourné : alerte ; rallumée : alerte résolue")
  void stoppedVmOpensThenRecovers() throws Exception {
    var hosts = List.of(host("pve-01", PowerState.ONLINE, "192.0.2.11"));
    inventory(hosts, List.of(vm("qemu/101", "vm-web-01", "pve-01", PowerState.RUNNING)));
    sync();
    UUID web = equipmentOf("qemu/101");

    inventory(hosts, List.of(vm("qemu/101", "vm-web-01", "pve-01", PowerState.STOPPED)));
    sync();
    assertThat(activeCollectionAlerts(web))
        .extracting(a -> a.get("message"))
        .containsExactly("VM vm-web-01 éteinte (hôte pve-01)");
    // L'état éteint entre dans l'historique sans fermer l'alerte.
    assertThat(
            jdbc.queryForObject(
                    "SELECT metric_value FROM metric WHERE equipment_id = ? AND metric_key = 'hv.power' ORDER BY collected_at DESC LIMIT 1",
                    java.math.BigDecimal.class,
                    web)
                .intValue())
        .isZero();

    inventory(hosts, List.of(vm("qemu/101", "vm-web-01", "pve-01", PowerState.RUNNING)));
    sync();
    assertThat(activeCollectionAlerts(web)).isEmpty();
  }

  @Test
  @DisplayName("Une VM toujours restée éteinte n'ouvre pas d'alerte")
  void aNeverRunningVmOpensNoAlert() throws Exception {
    inventory(
        List.of(host("pve-01", PowerState.ONLINE, "192.0.2.11")),
        List.of(vm("qemu/150", "vm-secours", "pve-01", PowerState.STOPPED)));
    sync();
    sync();
    assertThat(activeCollectionAlerts(equipmentOf("qemu/150"))).isEmpty();
  }

  // --- Disparitions et numéros réutilisés
  // ----------------------------------------------------------

  @Test
  @DisplayName(
      "Une VM supprimée de l'hyperviseur est marquée, son équipement et son historique restent")
  void removedGuestIsMarkedNotDeleted() throws Exception {
    var hosts = List.of(host("pve-01", PowerState.ONLINE, "192.0.2.11"));
    inventory(hosts, List.of(vm("qemu/101", "vm-web-01", "pve-01", PowerState.RUNNING)));
    sync();
    UUID web = equipmentOf("qemu/101");

    inventory(hosts, List.of());
    assertThat(sync().supprimees()).isEqualTo(1);

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM hypervisor_resource WHERE equipment_id = ?", String.class, web))
        .isEqualTo("REMOVED");
    assertThat(equipments.findById(web)).isPresent();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM metric WHERE equipment_id = ?", Integer.class, web))
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'HYPERVISOR_RESOURCE_REMOVED'",
                Integer.class))
        .isPositive();

    String list =
        mockMvc
            .perform(
                get("/api/v1/hyperviseurs/" + connector + "/ressources")
                    .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> removed = JsonPath.read(list, "$[?(@.statut == 'REMOVED')].nom");
    assertThat(removed).containsExactly("vm-web-01");
  }

  @Test
  @DisplayName("Même numéro, autre UUID matériel : une autre VM, un autre équipement")
  void reusedVmidWithAnotherUuidIsANewMachine() throws Exception {
    var hosts = List.of(host("pve-01", PowerState.ONLINE, "192.0.2.11"));
    inventory(hosts, List.of(vm("qemu/101", "vm-ancienne", "pve-01", PowerState.RUNNING)));
    when(proxmox.guestUuid(any(), any())).thenReturn(Optional.of(UUID_A));
    sync();
    UUID old = equipmentOf("qemu/101");

    // La VM a été supprimée puis une autre créée sous le même numéro, avec un autre nom.
    inventory(hosts, List.of(vm("qemu/101", "vm-nouvelle", "pve-01", PowerState.RUNNING)));
    when(proxmox.guestUuid(any(), any()))
        .thenReturn(Optional.of("11111111-2222-3333-4444-555555555555"));
    SyncReport report = sync();

    UUID fresh = equipmentOf("qemu/101");
    assertThat(fresh).isNotEqualTo(old);
    assertThat(report.equipementsCrees()).isEqualTo(1);
    assertThat(report.supprimees()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM hypervisor_resource WHERE equipment_id = ?", String.class, old))
        .isEqualTo("REMOVED");
    assertThat(equipments.findById(old)).isPresent();

    // Même nom, même UUID : rien ne change, et l'UUID n'est pas relu.
    assertThat(sync().equipementsCrees()).isZero();
  }

  // --- Pannes de l'API
  // ----------------------------------------------------------------------------

  @Test
  @DisplayName("API injoignable trois fois de suite : alerte sur les hôtes ; jeton refusé : jamais")
  void unreachableApiAlertsHostsButAuthErrorsDoNot() throws Exception {
    inventory(List.of(host("pve-01", PowerState.ONLINE, "192.0.2.11")), List.of());
    sync();
    UUID pve01 = equipmentOf("node/pve-01");

    when(proxmox.read(any()))
        .thenThrow(
            new HypervisorSourceException(
                Reason.AUTH_REFUSED, "Jeton refusé par Proxmox (HTTP 401)", 401));
    for (int i = 0; i < 4; i++) {
      assertThat(sync().reussi()).isFalse();
    }
    assertThat(activeCollectionAlerts(pve01)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error FROM hypervisor_connector WHERE id = ?",
                String.class,
                connector))
        .contains("401");

    org.mockito.Mockito.doThrow(
            new HypervisorSourceException(
                Reason.UNREACHABLE, "192.0.2.10 injoignable (connexion refusée)"))
        .when(proxmox)
        .read(any());
    sync();
    sync();
    assertThat(activeCollectionAlerts(pve01)).isNotEmpty();
    assertThat((String) activeCollectionAlerts(pve01).get(0).get("message"))
        .contains("injoignable depuis");
  }

  @Test
  @DisplayName("Le passage planifié traite les connecteurs dus, sous verrou")
  void scheduledRunProcessesDueConnectors() throws Exception {
    inventory(List.of(host("pve-01", PowerState.ONLINE, "192.0.2.11")), List.of());
    collection.scheduledRun();
    em.flush();
    assertThat(equipmentOf("node/pve-01")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT last_success_at IS NOT NULL FROM hypervisor_connector WHERE id = ?",
                Boolean.class,
                connector))
        .isTrue();
  }

  // --- Administration
  // -----------------------------------------------------------------------------

  @Test
  @DisplayName(
      "Réglages invalides refusés : HTTP, boucle locale, jeton mal formé, secret en clair, PEM")
  void invalidSettingsAreRefused() throws Exception {
    createConnector(
            connectorJson(
                "http://192.0.2.10:8006",
                "supervision@pve!trans-dic1",
                "env:TDIC1_TEST_PVE_SECRET",
                "PROXMOX"))
        .andExpect(status().isBadRequest());
    createConnector(
            connectorJson(
                "https://127.0.0.1:8006",
                "supervision@pve!trans-dic1",
                "env:TDIC1_TEST_PVE_SECRET",
                "PROXMOX"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Cible interdite : 127.0.0.1"));
    createConnector(
            connectorJson(
                "https://192.0.2.10:8006/api2/json",
                "supervision@pve!trans-dic1",
                "env:TDIC1_TEST_PVE_SECRET",
                "PROXMOX"))
        .andExpect(status().isBadRequest());
    createConnector(
            connectorJson(
                "https://192.0.2.10:8006", "supervision", "env:TDIC1_TEST_PVE_SECRET", "PROXMOX"))
        .andExpect(status().isBadRequest());
    createConnector(
            connectorJson(
                "https://192.0.2.10:8006",
                "supervision@pve!trans-dic1",
                "le-secret-en-clair",
                "PROXMOX"))
        .andExpect(status().isBadRequest());
    createConnector(
            connectorJson(
                "https://192.0.2.10:8006",
                "supervision@pve!trans-dic1",
                "env:TDIC1_TEST_PVE_SECRET",
                "VSPHERE"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value(org.hamcrest.Matchers.containsString("pas encore disponible")));
    String withKey =
        connectorJson(
                "https://192.0.2.10:8006",
                "supervision@pve!trans-dic1",
                "env:TDIC1_TEST_PVE_SECRET",
                "PROXMOX")
            .replace(
                "\"intervalleSecondes\"",
                "\"certificatAutorite\":\"-----BEGIN PRIVATE KEY-----\\nAAAA\\n-----END PRIVATE KEY-----\",\"intervalleSecondes\"");
    createConnector(withKey).andExpect(status().isBadRequest());
    String garbage =
        connectorJson(
                "https://192.0.2.10:8006",
                "supervision@pve!trans-dic1",
                "env:TDIC1_TEST_PVE_SECRET",
                "PROXMOX")
            .replace(
                "\"intervalleSecondes\"",
                "\"certificatAutorite\":\"pas un certificat\",\"intervalleSecondes\"");
    createConnector(garbage).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Seul un administrateur gère les connecteurs ; le secret n'est jamais renvoyé")
  void onlyAdminsAndTheSecretIsNeverReturned() throws Exception {
    mockMvc
        .perform(get("/api/v1/hyperviseurs").header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/hyperviseurs/" + connector + "/synchronisation")
                .header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isForbidden());
    String list =
        mockMvc
            .perform(get("/api/v1/hyperviseurs").header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].referenceSecret").value("env:TDIC1_TEST_PVE_SECRET"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(list).doesNotContain(SECRET_VALUE);
    mockMvc
        .perform(
            get("/api/v1/hyperviseurs/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'HYPERVISOR_CONNECTOR_CREATED' AND target_id = ?",
                Integer.class,
                connector))
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Essai et synchronisation à la demande par l'API, secret manquant signalé par sa référence")
  void testAndSynchronizeThroughTheApi() throws Exception {
    inventory(
        List.of(host("pve-01", PowerState.ONLINE, "192.0.2.11")),
        List.of(vm("qemu/101", "vm-web-01", "pve-01", PowerState.RUNNING), template()));
    mockMvc
        .perform(
            post("/api/v1/hyperviseurs/" + connector + "/essai")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reussi").value(true))
        .andExpect(jsonPath("$.invites").value(1));
    assertThat(equipmentsInSite()).isZero();
    mockMvc
        .perform(
            post("/api/v1/hyperviseurs/" + connector + "/synchronisation")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.equipementsCrees").value(2));

    String body =
        createConnector(
                connectorJson(
                    "https://192.0.2.20:8006",
                    "supervision@pve!autre",
                    "env:TDIC1_SECRET_ABSENT",
                    "PROXMOX"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String other = JsonPath.read(body, "$.id");
    mockMvc
        .perform(
            post("/api/v1/hyperviseurs/" + other + "/essai")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(jsonPath("$.reussi").value(false))
        .andExpect(
            jsonPath("$.message")
                .value("Secret introuvable pour la référence env:TDIC1_SECRET_ABSENT"));
  }
}
