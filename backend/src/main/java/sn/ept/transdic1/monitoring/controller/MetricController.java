package sn.ept.transdic1.monitoring.controller;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import sn.ept.transdic1.monitoring.dto.MetricResponse;
import sn.ept.transdic1.monitoring.service.MetricService;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricController {
  private final MetricService service;

  public MetricController(MetricService service) {
    this.service = service;
  }

  @GetMapping("/latest")
  public List<MetricResponse> latest() {
    return service.latest();
  }
}
