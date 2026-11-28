package sn.ept.transdic1.alerting.domain;

import java.util.UUID;

/**
 * Une alerte change de niveau de support (ADR 0059) : escalade au niveau supérieur, renvoi au
 * niveau inférieur, ou alerte critique montée d'office au plus haut niveau occupé. Le module
 * d'astreinte l'écoute pour choisir les destinataires (astreinte de ce niveau, sinon ses
 * opérateurs, sinon les administrateurs) et les prévenir ; alerting ne connaît ni l'astreinte ni
 * les notifications.
 *
 * @param eventId ligne de la chronologie qui porte le changement : clé d'idempotence des messages
 * @param toLevel niveau d'arrivée ; 4 quand aucun niveau supérieur n'est occupé (administrateurs)
 * @param recipientId destinataire choisi par l'auteur de l'escalade, ou nul
 * @param sentBack vrai pour un renvoi au niveau inférieur
 */
public record AlertLevelChangedEvent(
    UUID eventId,
    UUID alertId,
    Integer fromLevel,
    Integer toLevel,
    UUID recipientId,
    boolean sentBack) {}
