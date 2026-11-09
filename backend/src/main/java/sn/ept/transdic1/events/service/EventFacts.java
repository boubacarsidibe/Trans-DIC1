package sn.ept.transdic1.events.service;

import java.util.UUID;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;

/**
 * Ce qu'une règle regarde d'un événement : source, sévérité, OID de trap, texte (message syslog ou
 * variables du trap) et équipement avec son site, pour la portée.
 */
public record EventFacts(
    EventSource source,
    EventSeverity severity,
    String trapOid,
    String text,
    UUID equipmentId,
    UUID siteId) {}
