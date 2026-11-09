package sn.ept.transdic1.events.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import sn.ept.transdic1.inventory.domain.Equipment;

/** Trap SNMP ou message syslog reçu d'un équipement inventorié (ADR 0021). Jamais modifié. */
@Entity
@Table(name = "network_event")
public class NetworkEvent {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Equipment equipment;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 16)
  private EventSource source;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 16)
  private EventSeverity severity;

  @Column(name = "source_address", nullable = false, length = 64)
  private String sourceAddress;

  @Column(name = "title", nullable = false, length = 255)
  private String title;

  @Column(name = "details", columnDefinition = "text")
  private String details;

  @Column(name = "trap_oid", length = 255)
  private String trapOid;

  @Column(name = "hostname", length = 255)
  private String hostname;

  @Column(name = "app_name", length = 64)
  private String appName;

  @Column(name = "facility")
  private Short facility;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  protected NetworkEvent() {}

  public NetworkEvent(
      Equipment equipment,
      EventSource source,
      EventSeverity severity,
      String sourceAddress,
      String title,
      String details,
      String trapOid,
      Instant receivedAt) {
    id = UUID.randomUUID();
    this.equipment = equipment;
    this.source = source;
    this.severity = severity;
    this.sourceAddress = sourceAddress;
    this.title = title;
    this.details = details;
    this.trapOid = trapOid;
    this.receivedAt = receivedAt;
  }

  public UUID getId() {
    return id;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public EventSource getSource() {
    return source;
  }

  public EventSeverity getSeverity() {
    return severity;
  }

  public String getSourceAddress() {
    return sourceAddress;
  }

  public String getTitle() {
    return title;
  }

  public String getDetails() {
    return details;
  }

  public String getTrapOid() {
    return trapOid;
  }

  public String getHostname() {
    return hostname;
  }

  public void setHostname(String hostname) {
    this.hostname = hostname;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public Short getFacility() {
    return facility;
  }

  public void setFacility(Short facility) {
    this.facility = facility;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}
