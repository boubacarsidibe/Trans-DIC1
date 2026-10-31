package sn.ept.transdic1.inventory.dto;

import java.util.List;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;

/** Contrats de l'import CSV de l'inventaire (FEAT-07). */
public final class EquipmentImportDtos {
  private EquipmentImportDtos() {}

  /** Ligne valide, prête à être créée ; {@code line} est le numéro de ligne dans le fichier. */
  public record ImportRow(
      int line,
      String inventoryCode,
      String name,
      String managementAddress,
      EquipmentType type,
      EquipmentStatus status,
      String siteCode) {}

  /** Ligne en erreur (refusée) ou ignorée (vide, commentaire), avec la raison. */
  public record ImportIssue(int line, String message) {}

  /** Aperçu avant validation : rien n'est encore créé. */
  public record ImportPreview(
      List<ImportRow> valid, List<ImportIssue> errors, List<ImportIssue> ignored) {}

  /** Bilan d'un import : seules les lignes valides ont été créées. */
  public record ImportResult(int created, List<ImportIssue> errors, List<ImportIssue> ignored) {}
}
