package sn.ept.transdic1.diagnostic.service;

import java.util.List;
import java.util.UUID;

/**
 * Point d'extension des pistes du diagnostic (ADR 0024). Le catalogue fixe ({@link
 * NextStepCatalog}) en est l'implémentation par défaut ; les runbooks liés aux règles (F2.6)
 * fourniront une seconde implémentation, d'ordre ({@code @Order}) plus prioritaire, sans toucher au
 * service. Les pistes de tous les fournisseurs sont fusionnées dans l'ordre, sans doublon, et
 * plafonnées à cinq.
 */
public interface NextStepProvider {

  /** Ce qu'un fournisseur sait de l'alerte pour choisir ses pistes. */
  record Context(
      UUID alertId,
      String alertType,
      UUID ruleId,
      UUID eventRuleId,
      String metricKey,
      String collectionMode,
      List<Scored> causes) {}

  /** Pistes courtes, en français, de la plus utile à la moins utile ; jamais nul. */
  List<String> steps(Context context);
}
