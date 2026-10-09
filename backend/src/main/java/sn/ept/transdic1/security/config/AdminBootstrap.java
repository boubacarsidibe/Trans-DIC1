package sn.ept.transdic1.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.security.domain.*;
import sn.ept.transdic1.security.repository.UserRepository;

@Component
public class AdminBootstrap implements ApplicationRunner {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final String username, email, password;

  public AdminBootstrap(
      UserRepository u,
      PasswordEncoder e,
      @Value("${BOOTSTRAP_ADMIN_USERNAME:}") String n,
      @Value("${BOOTSTRAP_ADMIN_EMAIL:}") String mail,
      @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String p) {
    users = u;
    encoder = e;
    username = n;
    email = mail;
    password = p;
  }

  public void run(ApplicationArguments args) {
    if (!username.isBlank()
        && !email.isBlank()
        && !password.isBlank()
        && users.findByUsername(username).isEmpty())
      users.save(new AppUser(username, email, encoder.encode(password), Role.ADMIN));
  }
}
