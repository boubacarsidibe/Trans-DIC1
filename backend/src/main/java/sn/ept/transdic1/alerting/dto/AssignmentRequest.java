package sn.ept.transdic1.alerting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Assignation d'une alerte à un opérateur par un administrateur. */
public record AssignmentRequest(@NotNull UUID userId, @Size(max = 1000) String comment) {}
