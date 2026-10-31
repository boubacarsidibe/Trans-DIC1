package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Topologie : liens, refus des boucles, impact d'une panne en aval, droits. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TopologyTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;

  private String admin;
  private String viewer;
  private Site site;
  private Equipment core;
  private Equipment sw;
  private Equipment server;
  private Equipment vm;

  @BeforeEach
  void data() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(
            "adm-" + suffix,
            "adm-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    users.save(
        new AppUser(
            "vwr-" + suffix,
            "vwr-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.VIEWER));
    admin = login("adm-" + suffix);
    viewer = login("vwr-" + suffix);
    site = sites.save(new Site("TOP-" + suffix, "Site topologie"));
    core = equipment("Cœur", EquipmentType.ROUTER);
    sw = equipment("Commutateur", EquipmentType.SWITCH);
    server = equipment("Serveur", EquipmentType.SERVER);
    vm = equipment("Machine virtuelle", EquipmentType.VIRTUAL_MACHINE);
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

  private Equipment equipment(String name, EquipmentType type) {
    String unique = UUID.randomUUID().toString();
    var created =
        equipments.save(
            new Equipment(
                "TOP-EQ-" + unique,
                name,
                "10.232."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                type,
                EquipmentStatus.UP,
                site));
    equipments.flush();
    return created;
  }

  private ResultActions link(String jwt, Equipment child, Equipment upstream) throws Exception {
    return mockMvc.perform(
        post("/api/v1/topologie/liens")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"equipmentId\":\""
                    + child.getId()
                    + "\",\"upstreamId\":\""
                    + upstream.getId()
                    + "\"}"));
  }

  @Test
  void laPanneDUnAmontRendInjoignablesTousLesEquipementsEnAval() throws Exception {
    link(admin, sw, core).andExpect(status().isCreated());
    link(admin, server, sw).andExpect(status().isCreated());
    link(admin, vm, server).andExpect(status().isCreated());

    mockMvc
        .perform(
            get("/api/v1/topologie/impact/" + core.getId())
                .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dependents.length()").value(3))
        .andExpect(jsonPath("$.dependents[0].name").value("Commutateur"))
        .andExpect(jsonPath("$.dependents[2].name").value("Machine virtuelle"));
    mockMvc
        .perform(
            get("/api/v1/topologie/impact/" + vm.getId())
                .header("Authorization", "Bearer " + viewer))
        .andExpect(jsonPath("$.dependents.length()").value(0));
    mockMvc
        .perform(get("/api/v1/topologie").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.links[?(@.equipmentId=='" + vm.getId() + "')].upstreamId")
                .value(server.getId().toString()));
  }

  @Test
  void lesBouclesLesDoublonsEtLesLiensVersSoiSontRefuses() throws Exception {
    link(admin, sw, core).andExpect(status().isCreated());
    link(admin, server, sw).andExpect(status().isCreated());

    link(admin, sw, sw).andExpect(status().isBadRequest());
    link(admin, core, server).andExpect(status().isConflict());
    // En dernier : le doublon est refusé par la base, ce qui rend la transaction du test
    // inutilisable, or chaque requête authentifiée relit l'état du compte (FEAT-01).
    link(admin, sw, core).andExpect(status().isConflict());
  }

  @Test
  void seulUnAdministrateurModifieLesLiens() throws Exception {
    link(viewer, sw, core).andExpect(status().isForbidden());
    String id =
        JsonPath.read(link(admin, sw, core).andReturn().getResponse().getContentAsString(), "$.id");
    mockMvc
        .perform(
            delete("/api/v1/topologie/liens/" + id).header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(delete("/api/v1/topologie/liens/" + id).header("Authorization", "Bearer " + admin))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(delete("/api/v1/topologie/liens/" + id).header("Authorization", "Bearer " + admin))
        .andExpect(status().isNotFound());
  }
}
