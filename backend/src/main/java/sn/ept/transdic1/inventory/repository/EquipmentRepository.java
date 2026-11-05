package sn.ept.transdic1.inventory.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.inventory.domain.Equipment;

public interface EquipmentRepository
    extends JpaRepository<Equipment, UUID>, JpaSpecificationExecutor<Equipment> {
  /**
   * Relit un équipement en le verrouillant jusqu'à la fin de la transaction : sérialise les sondes
   * ICMP d'un même équipement qui décident de son statut.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from Equipment e where e.id = :id")
  Optional<Equipment> findForUpdate(@Param("id") UUID id);

  /** Nombre d'équipements par statut opérationnel : {statut, total}. */
  @org.springframework.data.jpa.repository.Query(
      "select e.status, count(e) from Equipment e group by e.status")
  List<Object[]> countByStatus();

  /** Même calcul limité au périmètre d'accès (ADR 0018). */
  @org.springframework.data.jpa.repository.Query(
      "select e.status, count(e) from Equipment e where e.site.id in :sites group by e.status")
  List<Object[]> countByStatusInSites(
      @org.springframework.data.repository.query.Param("sites") java.util.Collection<UUID> sites);

  boolean existsByInventoryCodeOrManagementAddress(String code, String address);

  boolean existsByManagementAddressAndIdNot(String address, UUID id);

  /** Équipement déjà inventorié à cette adresse (découverte, import). */
  java.util.Optional<Equipment> findFirstByManagementAddress(String address);

  /** Codes d'inventaire et adresses déjà utilisés parmi ceux fournis (détection des doublons). */
  @org.springframework.data.jpa.repository.Query(
      "select e.inventoryCode, e.managementAddress from Equipment e"
          + " where e.inventoryCode in :codes or e.managementAddress in :addresses")
  List<Object[]> findCodesAndAddresses(
      @org.springframework.data.repository.query.Param("codes") java.util.Collection<String> codes,
      @org.springframework.data.repository.query.Param("addresses")
          java.util.Collection<String> addresses);

  long countBySiteId(UUID siteId);

  /** Équipements des sites donnés (portée d'une règle testée sur l'historique). */
  List<Equipment> findBySiteIdIn(java.util.Collection<UUID> siteIds);

  /** Équipements à superviser : entrée du planificateur de collecte. */
  List<Equipment> findByMonitoringEnabledTrue();
}
