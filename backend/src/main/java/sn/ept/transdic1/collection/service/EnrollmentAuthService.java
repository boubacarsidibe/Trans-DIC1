package sn.ept.transdic1.collection.service;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentPrincipal;
import sn.ept.transdic1.inventory.repository.EnrollmentTokenRepository;

/**
 * Reconnaît un jeton d'inscription (ADR 0054). Il ne juge pas encore s'il est utilisable : un jeton
 * expiré, révoqué ou épuisé est reconnu ici pour que le refus soit tracé avec sa raison exacte par
 * {@link AgentEnrollmentService}. Un jeton inconnu, lui, n'est rattaché à rien.
 *
 * <p>La recherche se fait par empreinte SHA-256 dans un index : aucune comparaison de chaîne ne
 * porte sur le jeton lui-même, donc rien dont le temps de réponse trahisse un préfixe correct.
 */
@Service
public class EnrollmentAuthService {
  /**
   * Préfixe distinct de celui des jetons d'agent (tdic1_) : on les distingue au premier coup d'œil.
   */
  public static final String PREFIX = "tdic1e_";

  private final EnrollmentTokenRepository tokens;

  public EnrollmentAuthService(EnrollmentTokenRepository tokens) {
    this.tokens = tokens;
  }

  @Transactional(readOnly = true)
  public Optional<EnrollmentPrincipal> authenticate(String token) {
    if (token == null || !token.startsWith(PREFIX) || token.length() > 200) {
      return Optional.empty();
    }
    return tokens
        .findByTokenHash(AgentAuthService.hash(token))
        .map(found -> new EnrollmentPrincipal(found.getId()));
  }
}
