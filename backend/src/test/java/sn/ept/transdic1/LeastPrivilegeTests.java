package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moindre privilège (ADR 0058) : chaque endpoint réservé à l'administrateur par cette décision
 * répond 403 à l'opérateur NOC et au lecteur. Les corps sont valides, pour que le refus vienne bien
 * du rôle et non de la validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LeastPrivilegeTests {
  private static final String ID = UUID.randomUUID().toString();
  private static final String EQUIPMENT =
      """
      {"inventoryCode":"LP-1","name":"Moindre privilège","managementAddress":"192.0.2.250",
       "type":"SERVER","status":"UP","siteId":"%s"}
      """
          .formatted(ID);
  private static final String SITE = "{\"code\":\"LP-1\",\"name\":\"Moindre privilège\"}";
  private static final String MAINTENANCE =
      "{\"siteId\":\""
          + ID
          + "\",\"startAt\":\"2030-01-01T08:00:00Z\",\"endAt\":\"2030-01-01T10:00:00Z\","
          + "\"reason\":\"Essai\"}";

  @Autowired private MockMvc mockMvc;

  static Stream<Arguments> adminOnly() {
    var endpoints =
        Stream.of(
            // Parc et sites
            Arguments.of("POST", "/api/v1/equipements", EQUIPMENT),
            Arguments.of("PUT", "/api/v1/equipements/" + ID, EQUIPMENT),
            Arguments.of("DELETE", "/api/v1/equipements/" + ID, null),
            Arguments.of("POST", "/api/v1/sites", SITE),
            Arguments.of("PUT", "/api/v1/sites/" + ID, SITE),
            Arguments.of("DELETE", "/api/v1/sites/" + ID, null),
            // Import CSV
            Arguments.of("POST", "/api/v1/equipements/import/apercu", "csv"),
            Arguments.of("POST", "/api/v1/equipements/import", "csv"),
            // Dépendances entre services
            Arguments.of(
                "POST",
                "/api/v1/dependances",
                "{\"serviceId\":\"" + ID + "\",\"dependDeId\":\"" + ID + "\"}"),
            Arguments.of("DELETE", "/api/v1/dependances/" + ID, null),
            Arguments.of("POST", "/api/v1/dependances/import", "{\"csv\":\"a;b\"}"),
            // Suivi des services découverts
            Arguments.of(
                "POST", "/api/v1/equipements/" + ID + "/services-suivis", "{\"nom\":\"nginx\"}"),
            Arguments.of("DELETE", "/api/v1/equipements/" + ID + "/services-suivis/" + ID, null),
            // Actions correctives : approbation et refus
            Arguments.of("POST", "/api/v1/actions-correctives/" + ID + "/approbation", null),
            Arguments.of("POST", "/api/v1/actions-correctives/" + ID + "/refus", null),
            // Maintenances : planification, annulation, décision sur une demande
            Arguments.of("POST", "/api/v1/maintenances", MAINTENANCE),
            Arguments.of("POST", "/api/v1/maintenances/" + ID + "/annulation", null),
            Arguments.of("POST", "/api/v1/maintenances/demandes/" + ID + "/validation", null),
            Arguments.of(
                "POST", "/api/v1/maintenances/demandes/" + ID + "/refus", "{\"comment\":\"non\"}"),
            // Lectures réservées : règles, modèles, sécurité, modèle de risque, jetons
            Arguments.of("GET", "/api/v1/regles-alertes", null),
            Arguments.of("GET", "/api/v1/regles-alertes/" + ID, null),
            Arguments.of("GET", "/api/v1/regles-evenements", null),
            Arguments.of("GET", "/api/v1/regles-evenements/" + ID, null),
            Arguments.of("GET", "/api/v1/modeles", null),
            Arguments.of("GET", "/api/v1/modeles/linux-net-snmp", null),
            Arguments.of("GET", "/api/v1/securite", null),
            Arguments.of("GET", "/api/v1/risques/modele", null),
            Arguments.of("GET", "/api/v1/jetons", null),
            Arguments.of("POST", "/api/v1/jetons", "{\"name\":\"Script\"}"),
            Arguments.of("DELETE", "/api/v1/jetons/" + ID, null));
    return endpoints.flatMap(
        e ->
            Stream.of("NOC_OPERATOR", "VIEWER")
                .map(role -> Arguments.of(role, e.get()[0], e.get()[1], e.get()[2])));
  }

  @ParameterizedTest(name = "{0} {1} {2} : 403")
  @MethodSource("adminOnly")
  void reserveALAdministrateur(String role, String method, String path, String body)
      throws Exception {
    var call = request(HttpMethod.valueOf(method), path).with(user("lp-" + role).roles(role));
    if ("csv".equals(body)) {
      call = call.contentType("text/csv").content("code_inventaire;nom\n");
    } else if (body != null) {
      call = call.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    int status = mockMvc.perform(call).andReturn().getResponse().getStatus();
    assertThat(status).as("%s %s pour %s", method, path, role).isEqualTo(403);
  }
}
