package sn.ept.transdic1.threat.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.threat.domain.SecurityEvent;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, UUID> {}
