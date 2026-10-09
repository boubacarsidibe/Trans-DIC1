package sn.ept.transdic1.security.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.security.domain.AppUser;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
  Optional<AppUser> findByUsername(String username);
}
