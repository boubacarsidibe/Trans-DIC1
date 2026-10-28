package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** Gestion des sites : droits, code immuable, refus de supprimer un site occupé, audit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SiteApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String BASE = "/api/v1/sites";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;

  private String suffix;
  private String admin;
  private String noc;
  private String viewer;

  @BeforeEach
  void data() throws Exception {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(
            "adm-" + suffix,
            "adm-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    users.save(
        new AppUser(
            "noc-" + suffix,
            "noc-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.NOC_OPERATOR));
    users.save(
        new AppUser(
            "vwr-" + suffix,
            "vwr-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.VIEWER));
    admin = login("adm-" + suffix);
    noc = login("noc-" + suffix);
    viewer = login("vwr-" + suffix);
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

  private ResultActions call(String method, String jwt, String url, String body) throws Exception {
    var request =
        switch (method) {
          case "POST" -> post(url);
          case "PUT" -> put(url);
          case "DELETE" -> delete(url);
          default -> get(url);
        };
    if (jwt != null) {
      request = request.header("Authorization", "Bearer " + jwt);
    }
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(request);
  }

  private static String site(String code, String name) {
    return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"address\":\"Thiès\"}";
  }

  private String createSite(String code) throws Exception {
    var body =
        call("POST", noc, BASE, site(code, "Bâtiment " + code))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  @Test
  void everyRoleReadsButOnlyOperatorsAndAdministratorsWrite() throws Exception {
    String id = createSite("A" + suffix);

    for (String jwt : new String[] {admin, noc, viewer}) {
      call("GET", jwt, BASE + "/" + id, null).andExpect(status().isOk());
      call("GET", jwt, BASE, null).andExpect(status().isOk());
    }
    call("GET", null, BASE, null).andExpect(status().isUnauthorized());
    call("POST", viewer, BASE, site("B" + suffix, "Interdit")).andExpect(status().isForbidden());
    call("PUT", viewer, BASE + "/" + id, site("A" + suffix, "Interdit"))
        .andExpect(status().isForbidden());
    call("DELETE", viewer, BASE + "/" + id, null).andExpect(status().isForbidden());
  }

  @Test
  void createdSitesAreReturnedWithTheirDetailsAndAudited() throws Exception {
    call("POST", admin, BASE, site("C" + suffix, "Data center"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("C" + suffix))
        .andExpect(jsonPath("$.name").value("Data center"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.equipmentCount").value(0));

    em.flush();
    Integer audited =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_entry WHERE action = 'SITE_CREATED' AND username = ?",
            Integer.class,
            "adm-" + suffix);
    assertThat(audited).isEqualTo(1);
  }

  @Test
  void invalidAndDuplicateSitesAreRejected() throws Exception {
    createSite("D" + suffix);

    call("POST", noc, BASE, site("D" + suffix, "Doublon")).andExpect(status().isConflict());
    call("POST", noc, BASE, "{\"name\":\"Sans code\"}").andExpect(status().isBadRequest());
    call("POST", noc, BASE, site("code invalide", "X")).andExpect(status().isBadRequest());
    call("POST", noc, BASE, site("E" + suffix, "")).andExpect(status().isBadRequest());
  }

  @Test
  void updatingChangesTheFieldsButNeverTheCode() throws Exception {
    String id = createSite("F" + suffix);

    call(
            "PUT",
            noc,
            BASE + "/" + id,
            "{\"name\":\"Nouveau nom\",\"status\":\"INACTIVE\",\"description\":\"Fermé\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Nouveau nom"))
        .andExpect(jsonPath("$.status").value("INACTIVE"))
        .andExpect(jsonPath("$.code").value("F" + suffix));
    call("PUT", noc, BASE + "/" + id, site("G" + suffix, "Autre code"))
        .andExpect(status().isBadRequest());
    call("PUT", noc, BASE + "/" + UUID.randomUUID(), "{\"name\":\"X\"}")
        .andExpect(status().isNotFound());
  }

  @Test
  void anOccupiedSiteCannotBeDeletedButAnEmptyOneCan() throws Exception {
    String occupied = createSite("H" + suffix);
    Site entity = sites.findById(UUID.fromString(occupied)).orElseThrow();
    String unique = UUID.randomUUID().toString();
    equipments.save(
        new Equipment(
            "SIT-EQ-" + unique,
            "Switch du site",
            "10.238."
                + (Math.floorMod(unique.hashCode(), 250) + 1)
                + "."
                + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
            EquipmentType.SWITCH,
            EquipmentStatus.UP,
            entity));
    equipments.flush();

    call("GET", viewer, BASE + "/" + occupied, null)
        .andExpect(jsonPath("$.equipmentCount").value(1));
    call("DELETE", noc, BASE + "/" + occupied, null).andExpect(status().isConflict());

    String empty = createSite("I" + suffix);
    call("DELETE", noc, BASE + "/" + empty, null).andExpect(status().isNoContent());
    call("GET", noc, BASE + "/" + empty, null).andExpect(status().isNotFound());
  }

  @Test
  void theListFiltersByStatus() throws Exception {
    String id = createSite("J" + suffix);
    call("PUT", noc, BASE + "/" + id, "{\"name\":\"Fermé\",\"status\":\"INACTIVE\"}")
        .andExpect(status().isOk());

    var inactive =
        call("GET", viewer, BASE + "?status=INACTIVE&size=200", null)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(inactive).contains("J" + suffix);
    var active =
        call("GET", viewer, BASE + "?status=ACTIVE&size=200", null)
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(active).doesNotContain("J" + suffix);
  }
}
