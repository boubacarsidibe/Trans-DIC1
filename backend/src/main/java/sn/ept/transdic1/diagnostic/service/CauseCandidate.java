package sn.ept.transdic1.diagnostic.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.diagnostic.domain.CauseSource;

/**
 * Une cause candidate, avant score : sa source, la force de la preuve (0..1, fixée par la règle qui
 * l'a trouvée), l'instant de la preuve, et les phrases de preuve montrées à l'opérateur.
 */
public record CauseCandidate(
    CauseSource source,
    double strength,
    Instant at,
    String libelle,
    List<String> raisons,
    UUID equipementId,
    UUID alerteId) {

  public CauseCandidate {
    raisons = raisons == null ? List.of() : List.copyOf(raisons);
  }
}
