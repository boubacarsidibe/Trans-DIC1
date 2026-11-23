package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Incident (M10, ADR 0039) : un fait, décrit par plusieurs alertes. Une panne en amont qui fait
 * tomber cinq machines, une inondation qui touche tout un site, un serveur dont le processeur, la
 * mémoire et la latence partent ensemble — chacun de ces faits ouvrait jusqu'ici autant d'alertes
 * que de symptômes, et autant de notifications.
 *
 * <p>Le site est un simple identifiant et non une association : il sert au périmètre d'accès (ADR
 * 0018). C'est celui où l'incident a <b>commencé</b>, et il ne change plus : un incident qui
 * franchit un lien inter-sites reste rattaché à son site d'origine, faute de quoi un incident «
 * sans site » deviendrait invisible à tous les comptes limités à des sites.
 */
@Entity
@Table(name = "incident")
public class Incident {
  @Id private UUID id;

  @Column(name = "site_id")
  private UUID siteId;

  @Column(name = "title", nullable = false, length = 300)
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false)
  private Severity severity;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false)
  private IncidentState state;

  @Column(name = "opened_at", nullable = false)
  private Instant openedAt;

  /** Dernière alerte entrée dans l'incident : c'est elle qui ferme ou prolonge la fenêtre. */
  @Column(name = "last_alert_at", nullable = false)
  private Instant lastAlertAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  /**
   * Plus haute sévérité déjà notifiée pour cet incident, ou null si rien ne l'a été. Un booléen ne
   * suffirait pas : un incident notifié en avertissement qui devient critique doit réveiller
   * quelqu'un une seconde fois.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "notified_severity")
  private Severity notifiedSeverity;

  protected Incident() {}

  public Incident(UUID siteId, String title, Severity severity, Instant openedAt) {
    this.id = UUID.randomUUID();
    this.siteId = siteId;
    this.title = title;
    this.severity = severity;
    this.state = IncidentState.OPEN;
    this.openedAt = openedAt;
    this.lastAlertAt = openedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSiteId() {
    return siteId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public Severity getSeverity() {
    return severity;
  }

  public void setSeverity(Severity severity) {
    this.severity = severity;
  }

  public IncidentState getState() {
    return state;
  }

  public Instant getOpenedAt() {
    return openedAt;
  }

  public Instant getLastAlertAt() {
    return lastAlertAt;
  }

  public void setLastAlertAt(Instant lastAlertAt) {
    this.lastAlertAt = lastAlertAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public Severity getNotifiedSeverity() {
    return notifiedSeverity;
  }

  public void setNotifiedSeverity(Severity notifiedSeverity) {
    this.notifiedSeverity = notifiedSeverity;
  }

  /** Referme l'incident : toutes ses alertes sont retombées. */
  public void resolve(Instant at) {
    this.state = IncidentState.RESOLVED;
    this.resolvedAt = at;
  }
}
