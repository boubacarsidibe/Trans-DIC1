package sn.ept.transdic1.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sn.ept.transdic1.security.domain.*;

@Service
public class JwtService {
  private final byte[] secret;
  private final ObjectMapper json;

  public JwtService(@Value("${JWT_SECRET}") String s, ObjectMapper j) {
    if (s.length() < 32)
      throw new IllegalStateException("JWT_SECRET doit contenir au moins 32 caractères");
    secret = s.getBytes(StandardCharsets.UTF_8);
    json = j;
  }

  public String issue(AppUser u) {
    try {
      String h = enc(json.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
      String p =
          enc(
              json.writeValueAsBytes(
                  Map.of(
                      "sub",
                      u.getUsername(),
                      "role",
                      u.getRole().name(),
                      "exp",
                      Instant.now().plusSeconds(900).getEpochSecond())));
      return h + "." + p + "." + enc(sign(h + "." + p));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Claims verify(String t) {
    try {
      String[] p = t.split("\\.");
      if (p.length != 3
          || !MessageDigest.isEqual(sign(p[0] + "." + p[1]), Base64.getUrlDecoder().decode(p[2])))
        throw new IllegalArgumentException();
      Map<?, ?> m = json.readValue(Base64.getUrlDecoder().decode(p[1]), Map.class);
      if (((Number) m.get("exp")).longValue() < Instant.now().getEpochSecond())
        throw new IllegalArgumentException();
      return new Claims((String) m.get("sub"), Role.valueOf((String) m.get("role")));
    } catch (Exception e) {
      throw new IllegalArgumentException("Jeton invalide");
    }
  }

  private byte[] sign(String v) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(secret, "HmacSHA256"));
    return m.doFinal(v.getBytes(StandardCharsets.UTF_8));
  }

  private String enc(byte[] v) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(v);
  }

  public record Claims(String username, Role role) {}
}
