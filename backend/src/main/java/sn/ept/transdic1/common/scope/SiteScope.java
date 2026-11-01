package sn.ept.transdic1.common.scope;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Périmètre d'accès par site (FEAT-01, ADR 0018) : port lu par les modules métier, implémenté par
 * le module {@code access}. Les modules de base ne dépendent ainsi jamais de {@code access}.
 *
 * <p>Règles : un administrateur, un compte sans site attribué et les tâches internes (sans
 * utilisateur connecté) voient tout. Un opérateur ou un lecteur avec des sites attribués ne voit
 * que ces sites ; un équipement sans site lui est caché.
 */
public interface SiteScope {

  /** Sites visibles par l'utilisateur connecté ; vide = aucune restriction. */
  Optional<Set<UUID>> restriction();

  /** Le même calcul pour un autre utilisateur (destinataires de notifications, assignés). */
  Optional<Set<UUID>> restrictionOf(UUID userId);

  default boolean allows(UUID siteId) {
    return restriction().map(sites -> siteId != null && sites.contains(siteId)).orElse(true);
  }

  default boolean allowsUser(UUID userId, UUID siteId) {
    return restrictionOf(userId)
        .map(sites -> siteId != null && sites.contains(siteId))
        .orElse(true);
  }

  /**
   * Lecture ou action sur un objet existant hors périmètre : réponse « introuvable », pour ne pas
   * révéler qu'il existe.
   */
  default void requireVisible(UUID siteId, String notFoundMessage) {
    if (!allows(siteId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundMessage);
    }
  }

  /** Création ou rattachement vers un site hors périmètre : refus explicite. */
  default void requireAssignable(UUID siteId) {
    if (!allows(siteId)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Ce site est hors de votre périmètre d'accès.");
    }
  }

  /** Vrai pour un compte limité à certains sites. */
  default boolean restricted() {
    return restriction().isPresent();
  }
}
