package sn.ept.transdic1.collection.dto;

import java.time.Instant;

/** Dernier instantané reçu d'un équipement, avec son heure de réception. */
public record AgentSnapshotResponse(Instant receivedAt, AgentSnapshotDto snapshot) {}
