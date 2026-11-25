package sn.ept.transdic1.remediation.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.remediation.domain.RemediationPolicy;

public interface RemediationPolicyRepository extends JpaRepository<RemediationPolicy, UUID> {
  Optional<RemediationPolicy> findByRuleId(UUID ruleId);
}
