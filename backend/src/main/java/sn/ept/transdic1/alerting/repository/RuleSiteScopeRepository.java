package sn.ept.transdic1.alerting.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.*;

public interface RuleSiteScopeRepository extends JpaRepository<RuleSiteScope, UUID> {
  List<RuleSiteScope> findByRuleId(UUID ruleId);
}
