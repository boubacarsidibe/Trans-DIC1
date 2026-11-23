package sn.ept.transdic1.collection.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Lot de mesures relayé par un proxy de collecte (ADR 0047), groupé par équipement.
 *
 * @param lotId numéro du lot, choisi par le proxy : un rejeu du même numéro renvoie le premier
 *     bilan sans rien restocker, ce qui permet au proxy de renvoyer sans crainte quand il n'a pas
 *     reçu l'accusé de réception
 */
public record ProxyMeasuresRequest(
    @NotNull UUID lotId, @NotEmpty @Size(max = 200) List<@Valid EquipmentMeasures> equipements) {

  /** Les mesures d'un équipement du site, 500 au maximum. */
  public record EquipmentMeasures(
      @NotNull UUID equipementId, @NotEmpty @Size(max = 500) List<@Valid MeasureInput> mesures) {}
}
