package sn.ept.transdic1.events.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.events.domain.EventRule;

public interface EventRuleRepository extends JpaRepository<EventRule, UUID> {
  List<EventRule> findByActiveTrue();

  boolean existsByName(String name);

  boolean existsByNameAndIdNot(String name, UUID id);
}
