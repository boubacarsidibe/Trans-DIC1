package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.alerting.domain.LearnedCorrelation;
import sn.ept.transdic1.alerting.repository.LearnedCorrelationRepository;
import sn.ept.transdic1.alerting.service.AssociationRules;
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
 * API des corrélations apprises (M9, ADR 0042) : lecture pour tous, décision aux administrateurs,
 * et périmètre de sites appliqué aux deux équipements de la paire.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LearnedCorrelationApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private LearnedCorrelationRepository correlations;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;
  @Autowired private SiteAccessRepository access;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Site siteA;
  private Site siteB;
  private UUID proposalId;

  @BeforeEach
  void data() {
    jdbc.update("DELETE FROM learned_correlation");
    siteA = sites.saveAndFlush(new Site("LCA-" + suffix, "Site A"));
    siteB = sites.saveAndFlush(new Site("LCB-" + suffix, "Site B"));
    Equipment server = equipment("Serveur applicatif", siteA, 1);
    Equipment storage = equipment("Baie de stockage", siteA, 2);
    em.flush();
    var pair = AssociationRules.canonical(server.getId(), storage.getId());
    var correlation =
        new LearnedCorrelation(
            pair.get(0),
            pair.get(1),
            6,
            6,
            8,
            240,
            new BigDecimal("0.02500"),
            new BigDecimal("1.00000"),
            Instant.now());
    correlations.saveAndFlush(correlation);
    proposalId = correlation.getId();
    em.flush();
  }

  private Equipment equipment(String name, Site where, int host) {
    return equipments.saveAndFlush(
        new Equipment(
            "LC-" + UUID.randomUUID(),
            name,
            "10.92." + (Math.floorMod(suffix.hashCode(), 250) + 1) + "." + host,
            EquipmentType.SERVER,
            EquipmentStatus.UP,
            where));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  @DisplayName("La liste donne la paire, les comptes et la confiance")
  void thelistGivesThepairTheCountsAndTheconfidence() throws Exception {
    mockMvc
        .perform(get("/api/v1/correlations-apprises"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ensemble").value(6))
        .andExpect(jsonPath("$[0].fenetresTotales").value(240))
        .andExpect(jsonPath("$[0].confiance").value(1.0))
        .andExpect(jsonPath("$[0].support").value(0.025));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  @DisplayName("Un lecteur ne peut ni accepter ni écarter")
  void aviewerCanNeitherAcceptNorReject() throws Exception {
    mockMvc
        .perform(post("/api/v1/correlations-apprises/" + proposalId + "/acceptation"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post("/api/v1/correlations-apprises/" + proposalId + "/rejet"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Un administrateur accepte, et la proposition quitte la liste")
  void anadministratorAcceptsAndTheProposalLeavesTheList() throws Exception {
    users.saveAndFlush(
        new AppUser(
            "lc-adm-" + suffix,
            "lc-adm-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    em.flush();
    String token = login("lc-adm-" + suffix);

    mockMvc
        .perform(
            post("/api/v1/correlations-apprises/" + proposalId + "/acceptation")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
    // La décision passe par JPA et la liste relit en SQL : en production les deux requêtes sont
    // deux transactions, ici il faut vider la session entre les deux.
    em.flush();
    mockMvc
        .perform(get("/api/v1/correlations-apprises").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    // Deux fois la même décision est un conflit, pas un succès silencieux.
    mockMvc
        .perform(
            post("/api/v1/correlations-apprises/" + proposalId + "/rejet")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("Un opérateur limité au site B ne voit pas une paire du site A")
  void anoperatorLimitedToSiteBdoesNotSeeApairOfSiteA() throws Exception {
    AppUser limited =
        users.saveAndFlush(
            new AppUser(
                "lc-noc-" + suffix,
                "lc-noc-" + suffix + "@example.test",
                encoder.encode(PASSWORD),
                Role.NOC_OPERATOR));
    access.saveAndFlush(new SiteAccess(limited, siteB, Instant.now()));
    em.flush();
    String token = login("lc-noc-" + suffix);

    mockMvc
        .perform(get("/api/v1/correlations-apprises").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("Une proposition inconnue est introuvable, et l'authentification est requise")
  void anunknownProposalIsNotFoundAndAuthenticationIsRequired() throws Exception {
    users.saveAndFlush(
        new AppUser(
            "lc-adm2-" + suffix,
            "lc-adm2-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    em.flush();
    String token = login("lc-adm2-" + suffix);

    mockMvc
        .perform(
            post("/api/v1/correlations-apprises/" + UUID.randomUUID() + "/acceptation")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/correlations-apprises")).andExpect(status().isUnauthorized());
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
}
