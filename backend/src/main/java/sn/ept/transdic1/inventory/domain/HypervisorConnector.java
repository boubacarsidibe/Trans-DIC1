package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Connecteur d'hyperviseur (ADR 0055) : l'API interrogée, le jeton en lecture seule par sa seule
 * référence ({@code env:NOM}), le site où ranger les équipements découverts.
 */
@Entity
@Table(name = "hypervisor_connector")
public class HypervisorConnector {
  @Id private UUID id;

  @Column(nullable = false, length = 120)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private HypervisorKind kind;

  @Column(name = "api_url", nullable = false, length = 255)
  private String apiUrl;

  @Column(name = "token_id", nullable = false, length = 160)
  private String tokenId;

  @Column(name = "secret_reference", nullable = false, length = 80)
  private String secretReference;

  /** Second jeton, distinct, au seul droit VM.PowerMgmt (ADR 0057) ; absent par défaut. */
  @Column(name = "power_token_id", length = 160)
  private String powerTokenId;

  @Column(name = "power_secret_reference", length = 80)
  private String powerSecretReference;

  /** Actions sur les VM permises par ce connecteur ; désactivées par défaut. */
  @Column(name = "vm_actions_enabled", nullable = false)
  private boolean vmActionsEnabled;

  @Column(name = "ca_certificate", columnDefinition = "TEXT")
  private String caCertificate;

  @Column(name = "interval_seconds", nullable = false)
  private int intervalSeconds;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "site_id", nullable = false)
  private Site site;

  @Column(name = "create_icmp_probes", nullable = false)
  private boolean createIcmpProbes = true;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(name = "last_run_at")
  private Instant lastRunAt;

  @Column(name = "last_success_at")
  private Instant lastSuccessAt;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @Column(name = "consecutive_failures", nullable = false)
  private int consecutiveFailures;

  @Column(name = "created_by", length = 64)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected HypervisorConnector() {}

  public HypervisorConnector(
      String name, HypervisorKind kind, Site site, String createdBy, Instant createdAt) {
    id = UUID.randomUUID();
    this.name = name;
    this.kind = kind;
    this.site = site;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    updatedAt = createdAt;
  }

  /** Réglages modifiables par un administrateur. */
  public void configure(
      String name,
      String apiUrl,
      String tokenId,
      String secretReference,
      String caCertificate,
      int intervalSeconds,
      Site site,
      boolean createIcmpProbes,
      boolean enabled,
      Instant now) {
    this.name = name;
    this.apiUrl = apiUrl;
    this.tokenId = tokenId;
    this.secretReference = secretReference;
    this.caCertificate = caCertificate;
    this.intervalSeconds = intervalSeconds;
    this.site = site;
    this.createIcmpProbes = createIcmpProbes;
    this.enabled = enabled;
    updatedAt = now;
  }

  /** Jeton d'action sur les VM : tous deux vides, ou tous deux renseignés. */
  public void configureVmActions(
      String powerTokenId, String powerSecretReference, boolean vmActionsEnabled, Instant now) {
    this.powerTokenId = powerTokenId;
    this.powerSecretReference = powerSecretReference;
    this.vmActionsEnabled = vmActionsEnabled && powerTokenId != null;
    updatedAt = now;
  }

  /** Vrai si un administrateur peut demander le redémarrage d'une VM de ce connecteur. */
  public boolean allowsVmActions() {
    return vmActionsEnabled && powerTokenId != null && powerSecretReference != null;
  }

  public String getPowerTokenId() {
    return powerTokenId;
  }

  public String getPowerSecretReference() {
    return powerSecretReference;
  }

  public boolean isVmActionsEnabled() {
    return vmActionsEnabled;
  }

  /** Vrai si le prochain passage est dû à l'instant donné. */
  public boolean isDue(Instant now) {
    return enabled && (lastRunAt == null || !lastRunAt.plusSeconds(intervalSeconds).isAfter(now));
  }

  public void succeeded(Instant now) {
    lastRunAt = now;
    lastSuccessAt = now;
    lastError = null;
    consecutiveFailures = 0;
  }

  public void failed(String error, Instant now) {
    lastRunAt = now;
    lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
    consecutiveFailures++;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public HypervisorKind getKind() {
    return kind;
  }

  public String getApiUrl() {
    return apiUrl;
  }

  public String getTokenId() {
    return tokenId;
  }

  public String getSecretReference() {
    return secretReference;
  }

  public String getCaCertificate() {
    return caCertificate;
  }

  public int getIntervalSeconds() {
    return intervalSeconds;
  }

  public Site getSite() {
    return site;
  }

  public boolean isCreateIcmpProbes() {
    return createIcmpProbes;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Instant getLastRunAt() {
    return lastRunAt;
  }

  public Instant getLastSuccessAt() {
    return lastSuccessAt;
  }

  public String getLastError() {
    return lastError;
  }

  public int getConsecutiveFailures() {
    return consecutiveFailures;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
