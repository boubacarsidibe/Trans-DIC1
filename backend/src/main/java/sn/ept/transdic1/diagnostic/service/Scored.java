package sn.ept.transdic1.diagnostic.service;

/** Une cause candidate et son score (0..1, arrondi au millième). */
public record Scored(CauseCandidate candidate, double score) {}
