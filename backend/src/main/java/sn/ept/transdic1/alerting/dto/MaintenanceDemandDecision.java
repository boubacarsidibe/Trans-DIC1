package sn.ept.transdic1.alerting.dto;

import jakarta.validation.constraints.Size;

/** Décision sur une demande : le motif est obligatoire pour un refus, facultatif sinon. */
public record MaintenanceDemandDecision(@Size(max = 500) String comment) {}
