package sn.ept.transdic1.alerting.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.*;

public interface RuleEquipmentScopeRepository extends JpaRepository<RuleEquipmentScope, UUID> {
  List<RuleEquipmentScope> findByRuleId(UUID ruleId);
}
