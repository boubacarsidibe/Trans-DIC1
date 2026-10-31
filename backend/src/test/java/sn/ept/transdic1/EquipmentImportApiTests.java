package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Import CSV de l'inventaire (FEAT-07) : aperçu ligne par ligne, doublons, import, audit, rôles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EquipmentImportApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final MediaType CSV = MediaType.parseMediaType("text/csv");

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;

  private String noc;
  private String viewer;
  private Site site;
  private String p;

  @BeforeEach
  void data() throws Exception {
    p = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    users.save(
        new AppUser(
            "imp-noc-" + p, p + "n@example.test", encoder.encode(PASSWORD), Role.NOC_OPERATOR));
    users.save(
        new AppUser("imp-view-" + p, p + "v@example.test", encoder.encode(PASSWORD), Role.VIEWER));
    noc = login("imp-noc-" + p);
    viewer = login("imp-view-" + p);
    site = sites.save(new Site("IMP-" + p, "Site d'import"));
    equipments.save(
        new Equipment(
            "IMP-" + p + "-EXIST",
            "Existant",
            "198.51.100.210",
            EquipmentType.SERVER,
            EquipmentStatus.UP,
            site));
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM equipment WHERE site_id = ?", site.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
  }

  private String sample() {
    String s = "IMP-" + p;
    return "﻿code_inventaire;nom;adresse;type;statut;site\n"
        + s
        + "-01;Serveur web;198.51.100.110;SERVER;UP;"
        + s
        + "\n"
        + "\""
        + s
        + "-02\";\"Switch; étage 1\";198.51.100.111;switch;;"
        + s
        + "\n"
        + s
        + "-03;Borne;2001:db8::3;WIFI_ACCESS_POINT;DOWN;"
        + s
        + "\n"
        + "\n"
        + "# commentaire ignoré\n"
        + s
        + "-04;Mauvaise IP;198.51.100.1300;SERVER;UP;"
        + s
        + "\n"
        + s
        + "-05;Type inconnu;198.51.100.112;IMPRIMANTE;UP;"
        + s
        + "\n"
        + s
        + "-06;Site inconnu;198.51.100.113;SERVER;UP;SITE-ABSENT\n"
        + s
        + "-EXIST;Déjà en base;198.51.100.114;SERVER;UP;"
        + s
        + "\n"
        + s
        + "-07;Adresse en base;198.51.100.210;SERVER;UP;"
        + s
        + "\n"
        + s
        + "-01;Code répété;198.51.100.115;SERVER;UP;"
        + s
        + "\n"
        + s
        + "-08;Adresse répétée;198.51.100.110;SERVER;UP;"
        + s
        + "\n"
        + s
        + "-09;Colonnes manquantes;198.51.100.116\n"
        + s
        + "-10;\"Guillemet ouvert;198.51.100.117;SERVER;UP;"
        + s
        + "\n"
        + "-11;Code invalide;198.51.100.118;SERVER;UP;"
        + s
        + "\n"
        + s
        + "-12;Statut inconnu;198.51.100.119;SERVER;EN_PANNE;"
        + s
        + "\n"
        + s
        + "-13;exemple.org;exemple.org;SERVER;UP;"
        + s
        + "\n";
  }

  @Test
  void lApercuSepareValidesErreursEtIgnoreesSansRienCreer() throws Exception {
    long before = equipments.count();
    String body =
        send("/apercu", noc, sample())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat((java.util.List<String>) JsonPath.read(body, "$.valid[*].inventoryCode"))
        .containsExactly("IMP-" + p + "-01", "IMP-" + p + "-02", "IMP-" + p + "-03");
    assertThat((String) JsonPath.read(body, "$.valid[1].name")).isEqualTo("Switch; étage 1");
    assertThat((String) JsonPath.read(body, "$.valid[1].status")).isEqualTo("UNKNOWN");
    assertThat((String) JsonPath.read(body, "$.valid[1].type")).isEqualTo("SWITCH");
    assertThat((java.util.List<Integer>) JsonPath.read(body, "$.ignored[*].line"))
        .containsExactly(5, 6);
    assertThat((java.util.List<Integer>) JsonPath.read(body, "$.errors[*].line"))
        .containsExactly(7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);
    String errors = JsonPath.read(body, "$.errors[*].message").toString();
    assertThat(errors)
        .contains(
            "Adresse IP invalide",
            "Type inconnu",
            "Site inconnu",
            "code d'inventaire déjà présent",
            "adresse déjà utilisée",
            "même code d'inventaire qu'à la ligne 2",
            "même adresse qu'à la ligne 2",
            "6 colonnes attendues",
            "Guillemet non fermé",
            "Code d'inventaire invalide",
            "Statut inconnu");
    assertThat(equipments.count()).isEqualTo(before);
  }

  @Test
  void lImportCreeSeulementLesLignesValidesEtLesTrace() throws Exception {
    send("", noc, sample())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(3))
        .andExpect(jsonPath("$.errors.length()").value(12))
        .andExpect(jsonPath("$.ignored.length()").value(2));

    assertThat(
            jdbc.queryForList(
                "SELECT inventory_code FROM equipment WHERE site_id = ? ORDER BY inventory_code",
                String.class,
                site.getId()))
        .containsExactly(
            "IMP-" + p + "-01", "IMP-" + p + "-02", "IMP-" + p + "-03", "IMP-" + p + "-EXIST");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'EQUIPMENT_CREATED' AND details_sanitized LIKE ?",
                Integer.class,
                "code=IMP-" + p + "-%source=import"))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT details_sanitized FROM audit_entry WHERE action = 'EQUIPMENT_IMPORTED' AND username = ? ORDER BY performed_at DESC LIMIT 1",
                String.class,
                "imp-noc-" + p))
        .isEqualTo("crees=3 erreurs=12 ignorees=2");

    // Réimporter le même fichier ne crée rien : toutes les lignes sont devenues des doublons.
    send("", noc, sample()).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(0));
  }

  @Test
  void accepteLaVirguleCommeSeparateur() throws Exception {
    String csv =
        "code_inventaire,nom,adresse,type,statut,site\r\nIMP-"
            + p
            + "-V1,Routeur,198.51.100.130,ROUTER,UP,IMP-"
            + p
            + "\r\n";
    send("/apercu", noc, csv)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid[0].type").value("ROUTER"));
  }

  @Test
  void refuseUnFichierVideUnEnTeteInvalideOuTropLong() throws Exception {
    send("/apercu", noc, "\n \n").andExpect(status().isBadRequest());
    send("/apercu", noc, "code;nom\nA;B\n")
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString("code_inventaire")));
    StringBuilder big = new StringBuilder("code_inventaire;nom;adresse;type;statut;site\n");
    for (int i = 0; i < 1001; i++) {
      big.append("C").append(i).append(";N;198.51.100.11;SERVER;UP;S\n");
    }
    send("/apercu", noc, big.toString()).andExpect(status().isBadRequest());
    send("/apercu", noc, "x".repeat(1_000_001)).andExpect(status().isBadRequest());
  }

  @Test
  void reserveAuxRolesQuiPeuventModifierLInventaire() throws Exception {
    send("/apercu", viewer, sample()).andExpect(status().isForbidden());
    send("", viewer, sample()).andExpect(status().isForbidden());
  }

  private ResultActions send(String path, String jwt, String csv) throws Exception {
    return mockMvc.perform(
        post("/api/v1/equipements/import" + path)
            .header("Authorization", "Bearer " + jwt)
            .contentType(CSV)
            .content(csv));
  }

  private String login(String username) throws Exception {
    String body =
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
}
