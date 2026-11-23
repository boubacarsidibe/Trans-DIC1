package sn.ept.transdic1.alerting.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.LearnedCorrelation;
import sn.ept.transdic1.alerting.domain.LearnedCorrelationState;

public interface LearnedCorrelationRepository extends JpaRepository<LearnedCorrelation, UUID> {

  Optional<LearnedCorrelation> findByFirstEquipmentIdAndSecondEquipmentId(UUID first, UUID second);

  List<LearnedCorrelation> findByState(LearnedCorrelationState state);

  /** Les deux équipements d'un lien accepté, dans un sens ou dans l'autre. */
  List<LearnedCorrelation> findByStateAndFirstEquipmentIdOrStateAndSecondEquipmentId(
      LearnedCorrelationState firstState,
      UUID firstEquipmentId,
      LearnedCorrelationState secondState,
      UUID secondEquipmentId);
}
