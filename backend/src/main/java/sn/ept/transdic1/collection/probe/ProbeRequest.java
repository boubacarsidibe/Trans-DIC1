package sn.ept.transdic1.collection.probe;

import java.net.InetAddress;
import java.time.Duration;

/**
 * Cible d'une sonde. L'adresse est déjà validée par {@link ProbeTargetGuard} : les sondes se
 * connectent uniquement à elle et ne résolvent jamais le nom d'hôte.
 */
public record ProbeRequest(
    InetAddress address,
    String host,
    Integer port,
    String path,
    Duration timeout,
    String expectedStatus,
    String expectedText) {}
