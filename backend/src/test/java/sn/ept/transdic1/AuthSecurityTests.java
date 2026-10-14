package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityTests {
    private static final String PASSWORD = "Mot-de-passe-test-123";
    private static final String NEW_EQUIPMENT =
        """
        {"inventoryCode":"TEST-001","name":"Equipement test","managementAddress":"192.0.2.10",
         "type":"SERVER","status":"UP","siteId":"10000000-0000-0000-0000-000000000001"}
        """;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;

    @BeforeEach
    void prepareUsers() {
        users.deleteAll();
        users.save(new AppUser("admin-test", "admin-test@example.test", encoder.encode(PASSWORD), Role.ADMIN));
        users.save(new AppUser("viewer-test", "viewer-test@example.test", encoder.encode(PASSWORD), Role.VIEWER));
    }

    @Test
    void loginValideRetourneUnToken() throws Exception {
        mockMvc
            .perform(loginRequest("admin-test", PASSWORD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void motDePasseIncorrectRetourne401() throws Exception {
        mockMvc.perform(loginRequest("admin-test", "mauvais")).andExpect(status().isUnauthorized());
    }

    @Test
    void utilisateurInconnuRetourne401() throws Exception {
        mockMvc.perform(loginRequest("inconnu", PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void sansTokenRetourne401() throws Exception {
        mockMvc.perform(get("/api/v1/equipements")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenInvalideRetourne401() throws Exception {
        mockMvc
            .perform(get("/api/v1/equipements").header("Authorization", "Bearer abc.def.ghi"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenValidePermetLaLecture() throws Exception {
        String token = login("viewer-test");
        mockMvc
            .perform(get("/api/v1/equipements").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    void roleInsuffisantRetourne403() throws Exception {
        String token = login("viewer-test");
        mockMvc
            .perform(
                post("/api/v1/equipements")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(NEW_EQUIPMENT))
            .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder loginRequest(String username, String password) {
        return post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    void corsAutoriseLeFrontLocal() throws Exception {
        mockMvc
            .perform(
                options("/api/v1/equipements")
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void corsRefuseUneOrigineInconnue() throws Exception {
        mockMvc
            .perform(
                options("/api/v1/equipements")
                    .header("Origin", "http://site-inconnu.example")
                    .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
    }

    private String login(String username) throws Exception {
        MvcResult result =
            mockMvc.perform(loginRequest(username, PASSWORD)).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }
}
