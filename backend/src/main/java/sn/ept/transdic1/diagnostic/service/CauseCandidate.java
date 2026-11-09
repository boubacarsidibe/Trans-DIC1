package sn.ept.transdic1.diagnostic.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.diagnostic.domain.CauseSource;

/**
 * Une cause candidate, avant score : sa source, la force de la preuve (0..1, fixée par la règle qui
 * l'a trouvée), l'instant de la preuve, les phrases de preuve montrées à l'opérateur, et sa nature
 * (linkDown, agent, disk.usage...) qui sert au choix des pistes.
 */
public record CauseCandidate(
    CauseSource source,
    double strength,
    Instant at,
    String libelle,
    List<String> raisons,
    UUID equipementId,
    UUID alerteId,
    String nature) {

  public CauseCandidate {
    raisons = raisons == null ? List.of() : List.copyOf(raisons);
  }

  public CauseCandidate(
      CauseSource source,
      double strength,
      Instant at,
      String libelle,
      List<String> raisons,
      UUID equipementId,
      UUID alerteId) {
    this(source, strength, at, libelle, raisons, equipementId, alerteId, null);
  }
}
