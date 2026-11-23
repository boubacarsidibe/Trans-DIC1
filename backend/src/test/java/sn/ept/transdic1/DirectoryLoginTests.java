package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.port.Directory;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Connexion par l'annuaire (ADR 0046) de bout en bout, avec un annuaire de mentir.
 *
 * <p>Le dialogue réseau lui-même n'est pas éprouvé — il n'y a pas d'annuaire sur le poste de
 * développement — mais tout le reste l'est : création du compte à la première connexion, relecture
 * du rôle, refus quand aucun groupe n'est reconnu, protection d'un compte local homonyme, et
 * verrouillage après échecs répétés.
 */
@SpringBootTest(
    properties = {
      "ldap.group-admin=cri-admins",
      "ldap.group-operator=cri-noc",
      "ldap.group-viewer=cri-lecteurs"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DirectoryLoginTests {

  /** Annuaire de mentir : le test décide ce qu'il répond. */
  static final class FakeDirectory implements Directory {
    final AtomicReference<Boolean> on = new AtomicReference<>(true);
    final AtomicReference<Directory.DirectoryUser> answer = new AtomicReference<>(null);
    final AtomicReference<String> lastPassword = new AtomicReference<>(null);

    @Override
    public boolean enabled() {
      return on.get();
    }

    @Override
    public Optional<DirectoryUser> authenticate(String username, String password) {
      lastPassword.set(password);
      DirectoryUser known = answer.get();
      if (known == null || !known.username().equals(username)) {
        return Optional.empty();
      }
      // Le mot de passe de l'annuaire de mentir est « bon-mot-de-passe », et lui seul.
      return "bon-mot-de-passe".equals(password) ? Optional.of(known) : Optional.empty();
    }
  }

  @TestConfiguration
  static class Fake {
    @Bean
    @Primary
    FakeDirectory fakeDirectory() {
      return new FakeDirectory();
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private FakeDirectory fake;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder passwords;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private String username;

  @BeforeEach
  void data() {
    username = "khadija-" + UUID.randomUUID().toString().substring(0, 8);
    fake.on.set(true);
    fake.answer.set(
        new Directory.DirectoryUser(username, username + "@ept.sn", List.of("cri-noc")));
  }

  private int login(String password) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private Optional<AppUser> account() {
    em.flush();
    em.clear();
    return users.findByUsername(username);
  }

  @Test
  @DisplayName("Ce que l'annuaire apporte : le compte est créé à la première connexion")
  void whatTheDirectoryAddsTheaccountIsCreatedOnFirstLogin() throws Exception {
    assertThat(account()).isEmpty();

    assertThat(login("bon-mot-de-passe")).isEqualTo(200);

    var created = account().orElseThrow();
    assertThat(created.isDirectory()).isTrue();
    assertThat(created.getRole()).isEqualTo(Role.NOC_OPERATOR);
    assertThat(created.getEmail()).isEqualTo(username + "@ept.sn");
  }

  @Test
  @DisplayName("Le compte d'annuaire n'a pas de mot de passe utilisable")
  void thedirectoryAccountHasNousablePassword() throws Exception {
    login("bon-mot-de-passe");
    var created = account().orElseThrow();

    // Garder un second secret aurait annulé l'intérêt de déléguer : un mot de passe local périmé
    // continuerait d'ouvrir la porte après un changement côté annuaire.
    assertThat(passwords.matches("bon-mot-de-passe", created.getPasswordHash())).isFalse();
    assertThat(passwords.matches("", created.getPasswordHash())).isFalse();
  }

  @Test
  @DisplayName("Un mauvais mot de passe est refusé, et le compte n'est pas créé")
  void abadPasswordIsRefusedAndTheaccountIsNotCreated() throws Exception {
    assertThat(login("mauvais")).isEqualTo(401);

    assertThat(account()).isEmpty();
  }

  @Test
  @DisplayName("Aucun groupe reconnu vaut refus, et le journal le distingue d'une panne")
  void norecognisedGroupMeansRefusal() throws Exception {
    fake.answer.set(
        new Directory.DirectoryUser(username, username + "@ept.sn", List.of("etudiants")));

    assertThat(login("bon-mot-de-passe")).isEqualTo(401);

    assertThat(account()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'LOGIN_FAILED_NO_DIRECTORY_ROLE'",
                Integer.class))
        .isPositive();
  }

  @Test
  @DisplayName("Le rôle est relu à chaque connexion")
  void theroleIsReReadAtEveryLogin() throws Exception {
    login("bon-mot-de-passe");
    assertThat(account().orElseThrow().getRole()).isEqualTo(Role.NOC_OPERATOR);

    // La personne passe administrateur dans l'annuaire.
    fake.answer.set(
        new Directory.DirectoryUser(username, username + "@ept.sn", List.of("cri-admins")));
    assertThat(login("bon-mot-de-passe")).isEqualTo(200);

    assertThat(account().orElseThrow().getRole()).isEqualTo(Role.ADMIN);
  }

  @Test
  @DisplayName("Un compte local homonyme n'est pas remplacé à son insu")
  void alocalAccountWithTheSameNameIsNotReplaced() throws Exception {
    users.saveAndFlush(
        new AppUser(
            username, username + "@local.test", passwords.encode("secret-local"), Role.VIEWER));
    em.flush();

    // L'annuaire connaît aussi ce nom : la connexion par l'annuaire est refusée plutôt que de
    // transformer le compte local, et le mot de passe local continue de fonctionner.
    assertThat(login("bon-mot-de-passe")).isEqualTo(401);
    assertThat(account().orElseThrow().isDirectory()).isFalse();
    assertThat(account().orElseThrow().getRole()).isEqualTo(Role.VIEWER);
  }

  @Test
  @DisplayName("Un compte désactivé localement le reste, quoi qu'en dise l'annuaire")
  void anaccountDisabledLocallyStaysDisabled() throws Exception {
    login("bon-mot-de-passe");
    var created = account().orElseThrow();
    created.setActive(false);
    users.saveAndFlush(created);
    em.flush();

    assertThat(login("bon-mot-de-passe")).isEqualTo(401);
  }

  @Test
  @DisplayName("Sans annuaire configuré, rien ne change")
  void withoutAconfiguredDirectoryNothingChanges() throws Exception {
    fake.on.set(false);

    assertThat(login("bon-mot-de-passe")).isEqualTo(401);
    assertThat(account()).isEmpty();
  }

  @Test
  @DisplayName("Le mot de passe local d'un compte local n'est pas envoyé à l'annuaire")
  void thelocalPasswordOfAlocalAccountIsNotSentToTheDirectory() throws Exception {
    String local = "locale-" + UUID.randomUUID().toString().substring(0, 8);
    users.saveAndFlush(
        new AppUser(local, local + "@local.test", passwords.encode("secret-local"), Role.VIEWER));
    em.flush();
    fake.lastPassword.set(null);
    fake.answer.set(null);

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + local + "\",\"password\":\"secret-local\"}"))
        .andExpect(status().isOk());

    // Un compte local s'authentifie localement : son mot de passe ne part pas sur le réseau.
    assertThat(fake.lastPassword.get()).isNull();
  }

  @Test
  @DisplayName("Les échecs répétés verrouillent aussi un compte d'annuaire")
  void repeatedFailuresAlsoLockAdirectoryAccount() throws Exception {
    login("bon-mot-de-passe");

    for (int i = 0; i < 5; i++) {
      assertThat(login("mauvais")).isEqualTo(401);
    }

    // Le verrouillage protège l'annuaire autant que nous : sans lui, la plateforme servirait de
    // bélier pour essayer des mots de passe sur le compte de l'école.
    assertThat(account().orElseThrow().isLocked(java.time.Instant.now())).isTrue();
    assertThat(login("bon-mot-de-passe")).isEqualTo(401);
  }
}
