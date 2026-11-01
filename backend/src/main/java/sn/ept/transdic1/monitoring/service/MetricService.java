package sn.ept.transdic1.monitoring.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.monitoring.dto.MetricResponse;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

@Service
public class MetricService {
  private final MetricRepository repository;
  private final SiteScope scope;

  public MetricService(MetricRepository repository, SiteScope scope) {
    this.repository = repository;
    this.scope = scope;
  }

  @Transactional(readOnly = true)
  public List<MetricResponse> latest() {
    var visible = scope.restriction();
    var metrics =
        visible.isPresent()
            ? repository.findTop20ByEquipment_Site_IdInOrderByCollectedAtDesc(visible.get())
            : repository.findTop20ByOrderByCollectedAtDesc();
    return metrics.stream().map(MetricResponse::from).toList();
  }
}
