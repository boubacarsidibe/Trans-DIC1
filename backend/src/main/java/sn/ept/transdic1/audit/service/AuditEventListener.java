package sn.ept.transdic1.audit.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.common.audit.AuditRequested;

/**
 * Écrit au journal d'audit ce que demandent les modules qui n'ont pas le droit de dépendre de
 * celui-ci. L'écoute est synchrone : l'entrée part dans la transaction de l'appelant, et une action
 * annulée ne laisse pas de trace trompeuse.
 */
@Component
public class AuditEventListener {
  private final AuditService audit;

  public AuditEventListener(AuditService audit) {
    this.audit = audit;
  }

  @EventListener
  public void onAuditRequested(AuditRequested request) {
    audit.record(request.action(), request.targetType(), request.targetId(), request.details());
  }
}
