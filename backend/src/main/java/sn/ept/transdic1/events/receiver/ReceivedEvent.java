package sn.ept.transdic1.events.receiver;

import java.time.Instant;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;

/**
 * Événement décodé par un récepteur, pas encore rattaché à un équipement. {@code sourceAddress} est
 * l'adresse IP de l'émetteur du datagramme : c'est elle qui sert à authentifier la source.
 */
public record ReceivedEvent(
    EventSource source,
    String sourceAddress,
    EventSeverity severity,
    String title,
    String details,
    String trapOid,
    String hostname,
    String appName,
    Integer facility,
    Instant receivedAt) {}
