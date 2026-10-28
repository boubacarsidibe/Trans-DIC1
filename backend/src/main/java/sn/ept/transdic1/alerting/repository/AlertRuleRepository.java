package sn.ept.transdic1.alerting.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.*;

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {
  List<AlertRule> findByActiveTrue();

  boolean existsByName(String name);

  boolean existsByNameAndIdNot(String name, java.util.UUID id);

  /** Règles actives sur cet indicateur qui visent l'équipement lui-même ou son site. */
  @org.springframework.data.jpa.repository.Query(
      "select r from AlertRule r where r.active = true and r.metricType = :metricType and ("
          + "exists (select 1 from RuleEquipmentScope e where e.rule = r and e.active = true"
          + " and e.equipment.id = :equipmentId) or "
          + "exists (select 1 from RuleSiteScope s where s.rule = r and s.active = true"
          + " and s.site.id = :siteId))")
  List<AlertRule> findApplicable(
      @org.springframework.data.repository.query.Param("equipmentId") java.util.UUID equipmentId,
      @org.springframework.data.repository.query.Param("siteId") java.util.UUID siteId,
      @org.springframework.data.repository.query.Param("metricType") String metricType);
}
