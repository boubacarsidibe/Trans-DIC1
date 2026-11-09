package sn.ept.transdic1.oncall.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.oncall.domain.OnCallSchedule;

public interface OnCallScheduleRepository extends JpaRepository<OnCallSchedule, UUID> {
  @EntityGraph(attributePaths = "sites")
  List<OnCallSchedule> findByActiveTrue();

  @EntityGraph(attributePaths = "sites")
  List<OnCallSchedule> findAllByOrderByNameAsc();

  boolean existsByNameIgnoreCase(String name);

  boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
