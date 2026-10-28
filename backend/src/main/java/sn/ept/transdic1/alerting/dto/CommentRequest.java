package sn.ept.transdic1.alerting.dto;

import jakarta.validation.constraints.Size;

/** Commentaire facultatif joint à une action sur une alerte. */
public record CommentRequest(@Size(max = 1000) String comment) {}
