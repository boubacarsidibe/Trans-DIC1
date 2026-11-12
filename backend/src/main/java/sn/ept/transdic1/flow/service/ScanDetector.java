package sn.ept.transdic1.flow.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import sn.ept.transdic1.common.threat.NetworkScanDetected;

/**
 * Reconnaissance des balayages et des inondations dans les flux d'une minute (S6 et M7 du plan, ADR
 * 0033).
 *
 * <p>Calcul pur, sans base ni réseau : ce sont les seuils et leur justification qui décident de la
 * qualité de la détection, ils se mesurent donc sur des cas écrits.
 *
 * <p>Trois formes, chacune avec deux conditions : un <b>compte</b>, qui dit l'ampleur, et une
 * <b>entropie</b>, qui dit que la répartition est plate comme celle d'une machine et non creusée
 * comme celle d'un usage ordinaire. Le compte seul confondrait un serveur de sauvegarde avec un
 * scanner ; l'entropie seule crierait au loup sur trois machines.
 *
 * <ul>
 *   <li><b>Balayage d'adresses</b> : une source touche beaucoup de destinations, également, avec de
 *       tout petits échanges — personne ne transfère un fichier à deux cents machines en une minute
 *       à raison de quelques octets chacune.
 *   <li><b>Balayage de ports</b> : une source frappe beaucoup de ports d'une même destination.
 *   <li><b>Inondation</b> : une destination reçoit de beaucoup de sources à la fois, également
 *       réparties — la signature d'un déni de service distribué, ou d'une rafale usurpée.
 * </ul>
 */
public final class ScanDetector {

  /** Seuils, tous réglables : ce sont des choix raisonnés, pas des valeurs mesurées à l'EPT. */
  public record Thresholds(
      int scanTargets,
      int scanPorts,
      int floodSources,
      double minimumEntropy,
      long maximumBytesPerFlow) {

    public static Thresholds defaults() {
      return new Thresholds(200, 100, 200, 0.9, 1_000);
    }
  }

  /** Ce qui a été vu d'une source pendant la minute. */
  public record SourceView(
      String address,
      Map<String, Long> destinations,
      Map<String, Integer> portsPerDestination,
      long flows,
      long bytes,
      boolean saturated) {}

  /** Ce qui a été vu d'une destination pendant la minute. */
  public record DestinationView(
      String address, Map<String, Long> sources, long flows, boolean saturated) {}

  private ScanDetector() {}

  /**
   * Examine une minute d'observation et rend ce qu'il y a à signaler.
   *
   * @param equipmentId l'équipement qui a rapporté ces flux
   */
  public static List<NetworkScanDetected> examine(
      UUID equipmentId,
      Collection<SourceView> sources,
      Collection<DestinationView> destinations,
      Thresholds thresholds) {
    var findings = new ArrayList<NetworkScanDetected>();
    for (SourceView source : sources) {
      addressScan(equipmentId, source, thresholds).ifPresent(findings::add);
      portScan(equipmentId, source, thresholds).ifPresent(findings::add);
    }
    for (DestinationView destination : destinations) {
      flood(equipmentId, destination, thresholds).ifPresent(findings::add);
    }
    return List.copyOf(findings);
  }

  private static Optional<NetworkScanDetected> addressScan(
      UUID equipmentId, SourceView source, Thresholds thresholds) {
    int targets = source.destinations().size();
    if (targets < thresholds.scanTargets()) {
      return Optional.empty();
    }
    double entropy = FlowEntropy.normalised(source.destinations().values());
    if (entropy < thresholds.minimumEntropy()) {
      // Beaucoup de destinations, mais très inégalement : un serveur qui travaille, pas un scan.
      return Optional.empty();
    }
    long bytesPerFlow = source.flows() == 0 ? 0 : source.bytes() / source.flows();
    if (bytesPerFlow > thresholds.maximumBytesPerFlow()) {
      // Des échanges substantiels : c'est du trafic, pas du sondage.
      return Optional.empty();
    }
    return Optional.of(
        new NetworkScanDetected(
            equipmentId,
            NetworkScanDetected.Kind.ADDRESS_SCAN,
            source.address(),
            targets,
            source.saturated(),
            entropy,
            source.flows()));
  }

  private static Optional<NetworkScanDetected> portScan(
      UUID equipmentId, SourceView source, Thresholds thresholds) {
    String worst = null;
    int most = 0;
    for (var entry : source.portsPerDestination().entrySet()) {
      if (entry.getValue() > most) {
        most = entry.getValue();
        worst = entry.getKey();
      }
    }
    if (worst == null || most < thresholds.scanPorts()) {
      return Optional.empty();
    }
    return Optional.of(
        new NetworkScanDetected(
            equipmentId,
            NetworkScanDetected.Kind.PORT_SCAN,
            source.address(),
            most,
            source.saturated(),
            // L'entropie n'apporte rien ici : frapper cent ports d'une même machine n'a pas
            // d'équivalent légitime, quelle que soit la répartition.
            1,
            source.flows()));
  }

  private static Optional<NetworkScanDetected> flood(
      UUID equipmentId, DestinationView destination, Thresholds thresholds) {
    int sources = destination.sources().size();
    if (sources < thresholds.floodSources()) {
      return Optional.empty();
    }
    double entropy = FlowEntropy.normalised(destination.sources().values());
    if (entropy < thresholds.minimumEntropy()) {
      // Beaucoup de clients, mais inégalement : un serveur web fréquenté, pas une inondation.
      return Optional.empty();
    }
    return Optional.of(
        new NetworkScanDetected(
            equipmentId,
            NetworkScanDetected.Kind.FLOOD,
            destination.address(),
            sources,
            destination.saturated(),
            entropy,
            destination.flows()));
  }
}
