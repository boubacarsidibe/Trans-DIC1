package sn.ept.transdic1.common.threat;

import java.util.UUID;

/**
 * Balayage ou inondation repéré dans les flux réseau (S6, ADR 0033), publié par le module {@code
 * flow} et écouté par {@code threat}, qui ouvre l'alerte.
 *
 * <p>Ce détour par un événement commun suit la règle d'architecture : {@code flow} observe et ne
 * dépend d'aucun autre module, {@code threat} juge et alerte. Aucun des deux ne dépend de l'autre.
 *
 * <p>L'événement porte les <b>faits</b> et non une phrase toute faite : le module qui alerte
 * connaît le nom de l'équipement et la façon dont la plateforme formule ses messages.
 *
 * @param address l'adresse en cause : celle qui balaie, ou celle qui est inondée
 * @param count nombre de destinations, de ports ou de sources, selon la nature
 * @param countSaturated vrai si le comptage a été plafonné : le vrai nombre est plus grand
 * @param entropy répartition observée, de 0 (tout au même endroit) à 1 (parfaitement plate)
 */
public record NetworkScanDetected(
    UUID equipmentId,
    Kind kind,
    String address,
    int count,
    boolean countSaturated,
    double entropy,
    long flows) {

  /** Ce qui a été reconnu. */
  public enum Kind {
    /** Une source touche beaucoup de destinations différentes : recherche de machines. */
    ADDRESS_SCAN,
    /** Une source frappe beaucoup de ports d'une même machine : recherche de services. */
    PORT_SCAN,
    /** Une destination reçoit de beaucoup de sources à la fois : déni de service probable. */
    FLOOD
  }
}
