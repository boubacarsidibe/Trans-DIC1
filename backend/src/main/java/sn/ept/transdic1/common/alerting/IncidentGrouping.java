package sn.ept.transdic1.common.alerting;

import java.util.UUID;

/**
 * Regroupement des alertes en incidents (M10, ADR 0039) : port lu par les modules qui notifient,
 * implémenté par {@code alerting}. Même idiome que {@code common.scope.SiteScope} — {@code
 * reporting} et {@code oncall} n'ont pas le droit de dépendre des services d'{@code alerting} (voir
 * archunit.md), et c'est pourtant là que la décision doit se prendre.
 *
 * <p>Il répond à une seule question : <b>cette alerte parle-t-elle pour son incident ?</b> Quand
 * une panne en amont fait tomber cinq machines, une seule des cinq alertes prend la parole ; les
 * quatre autres sont visibles à l'écran, dans le même incident, mais ne réveillent personne.
 */
public interface IncidentGrouping {

  /**
   * Vrai si cette alerte est celle qui notifie pour son incident. Vrai aussi pour une alerte qui
   * n'appartient à aucun incident : le regroupement ne doit jamais faire taire une alerte qu'il n'a
   * pas rangée.
   *
   * <p>Lecture pure, sans effet : la décision est prise une fois, au rattachement, et plusieurs
   * chemins de notification (la file des notifications et l'astreinte) doivent pouvoir la lire
   * chacun de son côté sans se la voler.
   */
  boolean announces(UUID alertId);
}
