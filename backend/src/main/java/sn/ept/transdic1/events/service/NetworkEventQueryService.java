package sn.ept.transdic1.events.service;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.domain.NetworkEvent;
import sn.ept.transdic1.events.dto.NetworkEventResponse;
import sn.ept.transdic1.events.repository.NetworkEventRepository;

/** Consultation des événements réseau, limitée au périmètre d'accès par site (ADR 0018). */
@Service
public class NetworkEventQueryService {
  private final NetworkEventRepository events;
  private final SiteScope scope;

  public NetworkEventQueryService(NetworkEventRepository events, SiteScope scope) {
    this.events = events;
    this.scope = scope;
  }

  @Transactional(readOnly = true)
  public Page<NetworkEventResponse> list(
      UUID equipmentId,
      EventSource source,
      EventSeverity minSeverity,
      Instant from,
      Instant to,
      String q,
      Pageable pageable) {
    var visible = scope.restriction();
    Specification<NetworkEvent> spec =
        (root, query, cb) -> {
          var predicates = new ArrayList<Predicate>();
          if (equipmentId != null) {
            predicates.add(cb.equal(root.get("equipment").get("id"), equipmentId));
          }
          if (source != null) {
            predicates.add(cb.equal(root.get("source"), source));
          }
          if (minSeverity != null) {
            var accepted =
                Arrays.stream(EventSeverity.values()).filter(s -> s.atLeast(minSeverity)).toList();
            predicates.add(root.get("severity").in(accepted));
          }
          if (from != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("receivedAt"), from));
          }
          if (to != null) {
            predicates.add(cb.lessThan(root.get("receivedAt"), to));
          }
          if (q != null && !q.isBlank()) {
            String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
            predicates.add(
                cb.or(
                    cb.like(cb.lower(root.get("title")), like, '\\'),
                    cb.like(cb.lower(root.get("details")), like, '\\'),
                    cb.like(cb.lower(root.get("trapOid")), like, '\\')));
          }
          visible.ifPresent(
              ids -> predicates.add(root.get("equipment").get("site").get("id").in(ids)));
          return cb.and(predicates.toArray(Predicate[]::new));
        };
    return events.findAll(spec, pageable).map(NetworkEventResponse::from);
  }

  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
