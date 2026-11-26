package sn.ept.transdic1.collection.service;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.HypervisorDtos.ConnectorRequest;
import sn.ept.transdic1.collection.dto.HypervisorDtos.ConnectorResponse;
import sn.ept.transdic1.collection.dto.HypervisorDtos.ResourceResponse;
import sn.ept.transdic1.collection.dto.HypervisorDtos.SyncReport;
import sn.ept.transdic1.collection.dto.HypervisorDtos.TestResult;
import sn.ept.transdic1.collection.hypervisor.ProxmoxHypervisorSource;
import sn.ept.transdic1.collection.port.HypervisorSource;
import sn.ept.transdic1.collection.probe.ProbeTargetException;
import sn.ept.transdic1.collection.probe.ProbeTargetGuard;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.inventory.domain.HypervisorConnector;
import sn.ept.transdic1.inventory.domain.HypervisorResource;
import sn.ept.transdic1.inventory.domain.HypervisorResourceKind;
import sn.ept.transdic1.inventory.domain.HypervisorResourceStatus;
import sn.ept.transdic1.inventory.repository.HypervisorConnectorRepository;
import sn.ept.transdic1.inventory.repository.HypervisorResourceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Administration des connecteurs d'hyperviseur (ADR 0055). Chaque réglage est vérifié avant d'être
 * enregistré : adresse HTTPS acceptée par la garde anti-SSRF, référence de secret {@code env:NOM}
 * (jamais le secret), autorité de certification lisible, famille de connecteur disponible.
 */
@Service
public class HypervisorConnectorService {
  private static final String AUDIT_TARGET = "HYPERVISOR_CONNECTOR";
  private static final String NOT_FOUND = "Connecteur d'hyperviseur introuvable";

  /** Jeton Proxmox : utilisateur@domaine!nom (ex. supervision@pve!trans-dic1). */
  private static final Pattern TOKEN_ID = Pattern.compile("[^\\s!@]+@[^\\s!@]+![A-Za-z0-9._-]+");

  private final HypervisorConnectorRepository connectors;
  private final HypervisorResourceRepository resources;
  private final SiteRepository sites;
  private final HypervisorCollectionService collection;
  private final ProbeTargetGuard guard;
  private final AuditService audit;
  private final SiteScope scope;
  private final Clock clock;

  public HypervisorConnectorService(
      HypervisorConnectorRepository connectors,
      HypervisorResourceRepository resources,
      SiteRepository sites,
      HypervisorCollectionService collection,
      ProbeTargetGuard guard,
      AuditService audit,
      SiteScope scope,
      Clock clock) {
    this.connectors = connectors;
    this.resources = resources;
    this.sites = sites;
    this.collection = collection;
    this.guard = guard;
    this.audit = audit;
    this.scope = scope;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ConnectorResponse> list() {
    var visible = scope.restriction();
    return connectors.findAllWithSite().stream()
        .filter(c -> visible.map(ids -> ids.contains(c.getSite().getId())).orElse(true))
        .map(this::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public ConnectorResponse get(UUID id) {
    return toResponse(find(id));
  }

  @Transactional
  public ConnectorResponse create(ConnectorRequest request) {
    var site = validate(request);
    var connector =
        new HypervisorConnector(
            request.nom().trim(),
            request.type(),
            site,
            EnrollmentTokenService.currentUsername(),
            clock.instant());
    apply(connector, request, site);
    connectors.save(connector);
    audit.record(
        "HYPERVISOR_CONNECTOR_CREATED", AUDIT_TARGET, connector.getId(), details(connector));
    return toResponse(connector);
  }

  @Transactional
  public ConnectorResponse update(UUID id, ConnectorRequest request) {
    var connector = find(id);
    if (connector.getKind() != request.type()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "La famille d'un connecteur ne change pas : créez-en un autre.");
    }
    var site = validate(request);
    apply(connector, request, site);
    audit.record("HYPERVISOR_CONNECTOR_UPDATED", AUDIT_TARGET, id, details(connector));
    return toResponse(connector);
  }

  /**
   * Supprime le connecteur et la trace de ses ressources ; les équipements créés restent dans
   * l'inventaire, avec leur historique.
   */
  @Transactional
  public void delete(UUID id) {
    var connector = find(id);
    connectors.delete(connector);
    audit.record("HYPERVISOR_CONNECTOR_DELETED", AUDIT_TARGET, id, "nom=" + connector.getName());
  }

  @Transactional(readOnly = true)
  public List<ResourceResponse> resources(UUID id) {
    find(id);
    List<HypervisorResource> all = resources.findByConnector(id);
    Map<String, String> hostNames =
        all.stream()
            .filter(r -> r.getKind() == HypervisorResourceKind.HOST)
            .collect(
                Collectors.toMap(
                    HypervisorResource::getExternalId, HypervisorResource::getName, (a, b) -> a));
    return all.stream()
        .map(
            r ->
                new ResourceResponse(
                    r.getId(),
                    r.getKind(),
                    r.getName(),
                    r.getExternalId(),
                    r.getHostExternalId() == null
                        ? null
                        : hostNames.getOrDefault(r.getHostExternalId(), r.getHostExternalId()),
                    r.getPower(),
                    r.getIpAddress(),
                    r.getStatus(),
                    r.getEquipment() == null ? null : r.getEquipment().getId(),
                    r.getEquipment() == null ? null : r.getEquipment().getName(),
                    r.getLastSeenAt(),
                    r.getRemovedAt()))
        .toList();
  }

  /** Essai de connexion, sans rien créer (le connecteur doit être enregistré). */
  public TestResult test(UUID id) {
    requireVisible(id);
    return collection.test(id);
  }

  /** Passage immédiat, sans attendre l'intervalle. */
  public SyncReport synchronizeNow(UUID id) {
    requireVisible(id);
    SyncReport report = collection.synchronize(id);
    audit.record(
        "HYPERVISOR_CONNECTOR_SYNCHRONIZED",
        AUDIT_TARGET,
        id,
        report.reussi()
            ? "hotes="
                + report.hotes()
                + " invites="
                + report.invites()
                + " crees="
                + report.equipementsCrees()
            : "erreur=" + report.erreur());
    return report;
  }

  // --- Validation -------------------------------------------------------------------------------

  private sn.ept.transdic1.inventory.domain.Site validate(ConnectorRequest request) {
    scope.requireAssignable(request.siteId());
    var site =
        sites
            .findById(request.siteId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site inconnu"));
    boolean available =
        collection.source(request.type()).map(HypervisorSource::available).orElse(false);
    if (!available) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Le connecteur "
              + request.type()
              + " n'est pas encore disponible : seul Proxmox VE l'est.");
    }
    URI uri = apiUri(request.urlApi());
    try {
      guard.resolveAll(uri.getHost());
    } catch (ProbeTargetException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    if (!TOKEN_ID.matcher(request.identifiantJeton().trim()).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Identifiant de jeton attendu de la forme utilisateur@domaine!nom (ex. supervision@pve!trans-dic1)");
    }
    if (!SecretResolver.isSupported(request.referenceSecret())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Référence de secret de la forme env:NOM, jamais le secret lui-même");
    }
    String pem = blankToNull(request.certificatAutorite());
    if (pem != null) {
      if (pem.contains("PRIVATE KEY")) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Seul le certificat de l'autorité est attendu, jamais une clé privée.");
      }
      try {
        ProxmoxHypervisorSource.sslContext(pem);
      } catch (GeneralSecurityException | RuntimeException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Certificat d'autorité illisible (PEM attendu)");
      }
    }
    return site;
  }

  /**
   * Adresse de l'API ramenée à https://hôte[:port], sans chemin, ni identifiants, ni paramètres.
   */
  static URI apiUri(String url) {
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adresse de l'API invalide");
    }
    boolean bare =
        uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath());
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !bare) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Adresse de l'API attendue de la forme https://hôte:8006 (HTTPS, sans chemin)");
    }
    return URI.create("https://" + uri.getRawAuthority());
  }

  private void apply(
      HypervisorConnector connector,
      ConnectorRequest request,
      sn.ept.transdic1.inventory.domain.Site site) {
    connector.configure(
        request.nom().trim(),
        apiUri(request.urlApi()).toString(),
        request.identifiantJeton().trim(),
        request.referenceSecret().trim(),
        blankToNull(request.certificatAutorite()),
        request.intervalleSecondes(),
        site,
        request.creerSondesIcmp(),
        request.actif(),
        clock.instant());
  }

  private HypervisorConnector find(UUID id) {
    var connector =
        connectors
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
    scope.requireVisible(connector.getSite().getId(), NOT_FOUND);
    return connector;
  }

  @Transactional(readOnly = true)
  void requireVisible(UUID id) {
    find(id);
  }

  private static String details(HypervisorConnector c) {
    return "nom="
        + c.getName()
        + " type="
        + c.getKind()
        + " api="
        + c.getApiUrl()
        + " jeton="
        + c.getTokenId()
        + " secret="
        + c.getSecretReference()
        + " actif="
        + c.isEnabled();
  }

  private ConnectorResponse toResponse(HypervisorConnector c) {
    List<HypervisorResource> all = resources.findByConnector(c.getId());
    long hosts = count(all, HypervisorResourceKind.HOST);
    long vms = count(all, HypervisorResourceKind.VM);
    long containers = count(all, HypervisorResourceKind.CONTAINER);
    long removed =
        all.stream().filter(r -> r.getStatus() == HypervisorResourceStatus.REMOVED).count();
    return new ConnectorResponse(
        c.getId(),
        c.getName(),
        c.getKind(),
        c.getApiUrl(),
        c.getTokenId(),
        c.getSecretReference(),
        c.getCaCertificate(),
        c.getIntervalSeconds(),
        c.getSite().getId(),
        c.getSite().getName(),
        c.isCreateIcmpProbes(),
        c.isEnabled(),
        c.getLastRunAt(),
        c.getLastSuccessAt(),
        c.getLastError(),
        c.getConsecutiveFailures(),
        hosts,
        vms,
        containers,
        removed);
  }

  private static long count(List<HypervisorResource> all, HypervisorResourceKind kind) {
    return all.stream()
        .filter(r -> r.getKind() == kind && r.getStatus() == HypervisorResourceStatus.PRESENT)
        .count();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
