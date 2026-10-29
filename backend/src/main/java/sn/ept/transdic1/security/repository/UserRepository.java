package sn.ept.transdic1.security.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
  Optional<AppUser> findByUsername(String username);

  java.util.List<AppUser> findByRoleAndActiveTrue(sn.ept.transdic1.security.domain.Role role);

  boolean existsByEmail(String email);

  boolean existsByUsername(String username);

  long countByRoleAndActiveTrue(Role role);
}
