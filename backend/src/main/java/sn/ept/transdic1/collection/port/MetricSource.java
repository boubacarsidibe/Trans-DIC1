package sn.ept.transdic1.collection.port;

import java.time.Instant;
import java.util.List;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.inventory.domain.Equipment;

/**
 * Port de collecte : ce que sait faire n'importe quelle source de mesures (simulateur, lecture
 * SNMP, agent), sans rien supposer du protocole. Une source retourne des mesures déjà normalisées
 * (clé technique, valeur décimale, unité, horodatage UTC) ; la validation, le stockage et
 * l'évaluation des alertes restent communs, dans {@code MeasureIngestionService}.
 */
public interface MetricSource {
  /** Nom court de la source, utilisé dans les journaux et les compteurs. */
  String name();

  /** Lit les mesures de l'équipement à l'instant donné. */
  List<MeasureInput> read(Equipment equipment, Instant at) throws MetricSourceException;
}
