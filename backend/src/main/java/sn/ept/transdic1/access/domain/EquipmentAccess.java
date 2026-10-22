package sn.ept.transdic1.access.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "equipment_access")
public class EquipmentAccess {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  private Equipment equipment;

  @Column(name = "granted_at", nullable = false)
  private Instant grantedAt;

  protected EquipmentAccess() {}

  public EquipmentAccess(AppUser user, Equipment equipment, Instant grantedAt) {
    id = UUID.randomUUID();
    this.user = user;
    this.equipment = equipment;
    this.grantedAt = grantedAt;
  }

  public UUID getId() {
    return id;
  }

  public AppUser getUser() {
    return user;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public Instant getGrantedAt() {
    return grantedAt;
  }

  public void setGrantedAt(Instant grantedAt) {
    this.grantedAt = grantedAt;
  }
}
