package sn.ept.transdic1.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Modèle de message modifiable par un administrateur, sans redéploiement. */
@Entity
@Table(name = "notification_template")
public class NotificationTemplate {
  @Id
  @Column(name = "kind", length = 32)
  private String kind;

  @Column(name = "subject_template", nullable = false, length = 255)
  private String subjectTemplate;

  @Column(name = "body_template", nullable = false, length = 4000)
  private String bodyTemplate;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected NotificationTemplate() {}

  public String getKind() {
    return kind;
  }

  public String getSubjectTemplate() {
    return subjectTemplate;
  }

  public String getBodyTemplate() {
    return bodyTemplate;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void update(String subjectTemplate, String bodyTemplate, Instant now) {
    this.subjectTemplate = subjectTemplate;
    this.bodyTemplate = bodyTemplate;
    this.updatedAt = now;
  }
}
