package sn.ept.transdic1.audit.repository;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.audit.domain.AuditEntry;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {
  /** Historique d'un objet, du plus récent au plus ancien. */
  Page<AuditEntry> findByTargetTypeAndTargetIdOrderByPerformedAtDesc(
      String targetType, UUID targetId, Pageable pageable);

  Page<AuditEntry> findByAuthorIdOrderByPerformedAtDesc(UUID authorId, Pageable pageable);
}
