package sn.ept.transdic1.security.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.dto.*;
import sn.ept.transdic1.security.repository.UserRepository;

@Service
public class AuthService {
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final JwtService jwt;

  public AuthService(UserRepository u, PasswordEncoder p, JwtService j) {
    users = u;
    passwords = p;
    jwt = j;
  }

  public TokenResponse login(LoginRequest r) {
    var u =
        users
            .findByUsername(r.username())
            .filter(AppUser::isActive)
            .filter(x -> passwords.matches(r.password(), x.getPasswordHash()))
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identifiants invalides"));
    return new TokenResponse(jwt.issue(u), "Bearer", 900, u.getRole().name());
  }
}
