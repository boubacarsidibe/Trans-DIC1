package sn.ept.transdic1.events.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import sn.ept.transdic1.events.domain.EventRule;

public interface EventRuleRepository extends JpaRepository<EventRule, UUID> {
  List<EventRule> findByActiveTrue();

  /** Règles actives avec leur portée chargée d'une seule requête (cache des règles). */
  @Query(
      "select distinct r from EventRule r left join fetch r.siteIds left join fetch r.equipmentIds"
          + " where r.active = true")
  List<EventRule> findActiveWithScope();

  boolean existsByName(String name);

  boolean existsByNameAndIdNot(String name, UUID id);
}
