package sn.ept.transdic1.alerting.dto;

import java.util.List;

/** Une alerte et son historique complet, du plus ancien au plus récent. */
public record AlertDetailResponse(AlertResponse alert, List<AlertEventResponse> events) {}
