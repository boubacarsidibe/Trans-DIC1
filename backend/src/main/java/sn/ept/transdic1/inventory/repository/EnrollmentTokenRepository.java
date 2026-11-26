package sn.ept.transdic1.inventory.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.inventory.domain.EnrollmentToken;

public interface EnrollmentTokenRepository extends JpaRepository<EnrollmentToken, UUID> {
  /** Jeton présenté par un agent (par empreinte), quel que soit son état. */
  Optional<EnrollmentToken> findByTokenHash(String tokenHash);

  /**
   * Relit le jeton en le verrouillant : deux machines qui s'inscrivent au même instant ne doivent
   * pas dépasser ensemble le nombre maximal d'utilisations.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from EnrollmentToken t join fetch t.site where t.id = :id")
  Optional<EnrollmentToken> findForUpdate(@Param("id") UUID id);

  @Query("select t from EnrollmentToken t join fetch t.site order by t.createdAt desc")
  List<EnrollmentToken> findAllWithSite();
}
