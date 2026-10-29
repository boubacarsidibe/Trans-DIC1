package sn.ept.transdic1.reporting.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.reporting.domain.NotificationTemplate;

public interface NotificationTemplateRepository
    extends JpaRepository<NotificationTemplate, String> {
  List<NotificationTemplate> findAllByOrderByKindAsc();
}
