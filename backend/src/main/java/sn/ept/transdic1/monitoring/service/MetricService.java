package sn.ept.transdic1.monitoring.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.monitoring.dto.MetricResponse;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

@Service
public class MetricService {
  private final MetricRepository repository;

  public MetricService(MetricRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public List<MetricResponse> latest() {
    return repository.findTop20ByOrderByCollectedAtDesc().stream()
        .map(MetricResponse::from)
        .toList();
  }
}
