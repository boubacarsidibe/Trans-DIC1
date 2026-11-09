package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Modèle d'équipement appliqué (ADR 0022) : dernière version appliquée, quand et par qui. */
@Entity
@Table(name = "equipment_template")
public class EquipmentTemplateApplication {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Equipment equipment;

  @Column(name = "template_id", nullable = false, length = 64)
  private String templateId;

  @Column(name = "template_version", nullable = false)
  private int templateVersion;

  @Column(name = "applied_at", nullable = false)
  private Instant appliedAt;

  @Column(name = "applied_by", length = 255)
  private String appliedBy;

  protected EquipmentTemplateApplication() {}

  public EquipmentTemplateApplication(Equipment equipment, String templateId) {
    id = UUID.randomUUID();
    this.equipment = equipment;
    this.templateId = templateId;
  }

  /** Nouvelle application : version, date et auteur remplacent les précédents. */
  public void record(int version, Instant at, String by) {
    templateVersion = version;
    appliedAt = at;
    appliedBy = by;
  }

  public UUID getId() {
    return id;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public String getTemplateId() {
    return templateId;
  }

  public int getTemplateVersion() {
    return templateVersion;
  }

  public Instant getAppliedAt() {
    return appliedAt;
  }

  public String getAppliedBy() {
    return appliedBy;
  }
}
