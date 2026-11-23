package sn.ept.transdic1.collection.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.ProxyRegistrationResponse;
import sn.ept.transdic1.collection.dto.ProxyStatusResponse;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionProxy;
import sn.ept.transdic1.inventory.repository.CollectionProxyRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/** Enregistre et révoque le proxy de collecte d'un site (ADR 0047), un seul actif à la fois. */
@Service
public class ProxyRegistrationService {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SiteRepository sites;
  private final CollectionProxyRepository proxies;
  private final AuditService audit;
  private final Clock clock;
  private final Duration silence;

  public ProxyRegistrationService(
      SiteRepository sites,
      CollectionProxyRepository proxies,
      AuditService audit,
      Clock clock,
      @Value("${collection.proxy.silence-seconds:600}") long silenceSeconds) {
    this.sites = sites;
    this.proxies = proxies;
    this.audit = audit;
    this.clock = clock;
    this.silence = Duration.ofSeconds(silenceSeconds);
  }

  /**
   * Crée un proxy actif et son jeton. Un proxy déjà actif sur ce site est révoqué : changer de
   * jeton, c'est enregistrer à nouveau.
   */
  @Transactional
  public ProxyRegistrationResponse register(UUID siteId) {
    var site =
        sites
            .findById(siteId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site introuvable"));
    var now = clock.instant();
    proxies
        .findBySiteIdAndStatus(siteId, AgentStatus.ACTIVE)
        .ifPresent(
            previous -> {
              previous.setStatus(AgentStatus.REVOKED);
              previous.setRevokedAt(now);
              proxies.saveAndFlush(previous);
              audit.record("PROXY_REVOKED", "SITE", siteId, "proxy=" + previous.getIdentifier());
            });

    String token = newToken();
    String identifier = "proxy-" + UUID.randomUUID().toString().substring(0, 8);
    var proxy = new CollectionProxy(site, identifier, AgentStatus.ACTIVE, now);
    proxy.setTokenHash(AgentAuthService.hash(token));
    proxies.save(proxy);
    audit.record("PROXY_REGISTERED", "SITE", siteId, "proxy=" + identifier);
    return new ProxyRegistrationResponse(proxy.getId(), identifier, token);
  }

  /** Le proxy actif du site, ou 404 s'il n'y en a pas. */
  @Transactional(readOnly = true)
  public ProxyStatusResponse current(UUID siteId) {
    var proxy =
        proxies
            .findBySiteIdAndStatus(siteId, AgentStatus.ACTIVE)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Aucun proxy actif pour ce site"));
    return toResponse(proxy);
  }

  @Transactional
  public void revoke(UUID siteId) {
    var proxy =
        proxies
            .findBySiteIdAndStatus(siteId, AgentStatus.ACTIVE)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Aucun proxy actif pour ce site"));
    proxy.setStatus(AgentStatus.REVOKED);
    proxy.setRevokedAt(clock.instant());
    proxies.save(proxy);
    audit.record("PROXY_REVOKED", "SITE", siteId, "proxy=" + proxy.getIdentifier());
  }

  private ProxyStatusResponse toResponse(CollectionProxy proxy) {
    boolean silencieux =
        Duration.between(proxy.lastSignOfLife(), clock.instant()).compareTo(silence) > 0;
    return new ProxyStatusResponse(
        proxy.getId(),
        proxy.getIdentifier(),
        proxy.getStatus(),
        proxy.getRegisteredAt(),
        proxy.getLastCommunicationAt(),
        proxy.getIpAddress(),
        silencieux);
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "tdic1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
