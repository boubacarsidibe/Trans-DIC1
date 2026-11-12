package sn.ept.transdic1.threat.service;

import java.time.Clock;
import java.util.Locale;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.common.threat.NetworkScanDetected;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Ouvre les alertes de sécurité pour les balayages et les inondations repérés dans les flux réseau
 * (S6, ADR 0033).
 *
 * <p>Le module qui observe les flux ne dépend d'aucun autre : il publie ce qu'il a vu, et c'est ici
 * — là où vivent déjà les alertes de sécurité — qu'on décide de la gravité et qu'on écrit la phrase
 * que lira l'équipe.
 *
 * <p>Un balayage est une <b>reconnaissance</b> : quelqu'un cherche ce qui existe. C'est un
 * avertissement, pas une attaque réussie. Une inondation, elle, se voit déjà dans le service qui
 * tombe : elle est critique, parce qu'elle demande une action immédiate.
 */
@Component
public class NetworkScanListener {
  private final EquipmentRepository equipments;
  private final SecurityAlerts alerts;
  private final Clock clock;

  public NetworkScanListener(EquipmentRepository equipments, SecurityAlerts alerts, Clock clock) {
    this.equipments = equipments;
    this.alerts = alerts;
    this.clock = clock;
  }

  @EventListener
  @Transactional
  public void onNetworkScanDetected(NetworkScanDetected finding) {
    Equipment equipment = equipments.findById(finding.equipmentId()).orElse(null);
    if (equipment == null) {
      return;
    }
    alerts.open(
        equipment,
        subject(finding.kind()),
        finding.kind().name() + ":" + finding.address(),
        severity(finding.kind()),
        message(finding, equipment),
        clock.instant());
  }

  private static SecurityAlerts.Subject subject(NetworkScanDetected.Kind kind) {
    return kind == NetworkScanDetected.Kind.FLOOD
        ? SecurityAlerts.Subject.FLOOD
        : SecurityAlerts.Subject.NETWORK_SCAN;
  }

  private static Severity severity(NetworkScanDetected.Kind kind) {
    // Une reconnaissance se regarde ; une inondation se traite tout de suite.
    return kind == NetworkScanDetected.Kind.FLOOD ? Severity.CRITICAL : Severity.WARNING;
  }

  private static String message(NetworkScanDetected finding, Equipment equipment) {
    String count = (finding.countSaturated() ? "plus de " : "") + finding.count();
    return switch (finding.kind()) {
      case ADDRESS_SCAN ->
          "Balayage d'adresses depuis "
              + finding.address()
              + ", vu par "
              + equipment.getName()
              + " : "
              + count
              + " machines contactées en une minute, toutes de la même façon ("
              + spread(finding.entropy())
              + "), en échanges minuscules. Quelqu'un cherche ce qui existe sur le réseau.";
      case PORT_SCAN ->
          "Balayage de ports depuis "
              + finding.address()
              + ", vu par "
              + equipment.getName()
              + " : "
              + count
              + " ports frappés sur une même machine en une minute. Quelqu'un cherche quels"
              + " services tournent.";
      case FLOOD ->
          "Inondation vers "
              + finding.address()
              + ", vue par "
              + equipment.getName()
              + " : "
              + count
              + " sources différentes en une minute ("
              + finding.flows()
              + " flux), également réparties ("
              + spread(finding.entropy())
              + "). Déni de service probable : vérifier que le service répond encore.";
    };
  }

  /** Dit l'entropie en clair : « répartition 0,98 sur 1 » plutôt qu'un nombre seul. */
  private static String spread(double entropy) {
    return String.format(Locale.FRANCE, "répartition %.2f sur 1", entropy);
  }
}
