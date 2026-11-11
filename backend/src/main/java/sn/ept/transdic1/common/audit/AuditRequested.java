package sn.ept.transdic1.common.audit;

import java.util.UUID;

/**
 * Demande d'écriture au journal d'audit, publiée par un module qui n'a pas le droit de dépendre du
 * module d'audit (règle d'architecture : {@code reporting} ne dépend pas de {@code audit}). Le
 * module d'audit l'écoute et écrit l'entrée.
 *
 * <p>L'écouteur travaille dans le même fil et la même transaction que l'émetteur : l'utilisateur
 * connecté et l'identifiant de corrélation sont donc ceux de la requête en cours, comme pour un
 * appel direct.
 */
public record AuditRequested(String action, String targetType, UUID targetId, String details) {}
