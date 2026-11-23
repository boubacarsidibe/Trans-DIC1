package sn.ept.transdic1.inventory.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import sn.ept.transdic1.inventory.domain.ProxyBatch;

public interface ProxyBatchRepository extends JpaRepository<ProxyBatch, UUID> {

  Optional<ProxyBatch> findByProxyIdAndLotId(UUID proxyId, UUID lotId);

  /** Oublie les lots trop vieux : la trace ne sert qu'à reconnaître un rejeu immédiat. */
  @Modifying
  @Query("delete from ProxyBatch b where b.receivedAt < :before")
  int deleteOlderThan(Instant before);
}
