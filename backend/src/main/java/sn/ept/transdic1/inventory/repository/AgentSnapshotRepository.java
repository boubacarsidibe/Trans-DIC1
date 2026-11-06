package sn.ept.transdic1.inventory.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.AgentSnapshot;

public interface AgentSnapshotRepository extends JpaRepository<AgentSnapshot, UUID> {}
