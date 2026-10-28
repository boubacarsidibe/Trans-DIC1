package sn.ept.transdic1.collection.dto;

/** Bilan d'une réception de mesures. */
public record IngestionResult(int stored, int evaluated, int alertsOpened, int alertsResolved) {}
