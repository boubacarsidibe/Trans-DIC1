package sn.ept.transdic1.security.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import sn.ept.transdic1.security.dto.*;
import sn.ept.transdic1.security.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService service;

  public AuthController(AuthService service) {
    this.service = service;
  }

  @PostMapping("/login")
  TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return service.login(request);
  }
}
