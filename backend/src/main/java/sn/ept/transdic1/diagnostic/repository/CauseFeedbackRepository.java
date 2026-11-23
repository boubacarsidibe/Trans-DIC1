package sn.ept.transdic1.diagnostic.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.diagnostic.domain.CauseFeedback;

public interface CauseFeedbackRepository extends JpaRepository<CauseFeedback, UUID> {

  List<CauseFeedback> findByAlertId(UUID alertId);

  void deleteByAlertId(UUID alertId);
}
