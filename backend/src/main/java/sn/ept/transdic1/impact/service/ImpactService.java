package sn.ept.transdic1.impact.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.impact.dto.ImpactResponse;
import sn.ept.transdic1.impact.dto.ImpactResponse.Component;
import sn.ept.transdic1.impact.dto.ImpactResponse.Downstream;
import sn.ept.transdic1.impact.dto.ImpactResponse.LearnedLink;
import sn.ept.transdic1.impact.dto.ImpactResponse.Maintenance;
import sn.ept.transdic1.impact.dto.ImpactResponse.ServiceLine;
import sn.ept.transdic1.impact.dto.ImpactResponse.Target;
import sn.ept.transdic1.impact.repository.ImpactRepository;
import sn.ept.transdic1.impact.repository.ImpactRepository.DownstreamRow;
import sn.ept.transdic1.impact.repository.ImpactRepository.EquipmentRow;
import sn.ept.transdic1.impact.repository.ImpactRepository.LinkRow;

/**
 * Analyse d'impact (« que se passe-t-il si cet équipement s'arrête ? ») : avant une maintenance, ou
 * pendant une panne, dire ce qui tombe avec lui.
 *
 * <p>L'aval vient de la topologie (saisie ou acceptée depuis LLDP). Un équipement en aval qui a un
 * <b>second chemin</b> vers le cœur du réseau, par un amont qui n'est pas lui-même touché, est
 * signalé comme redondant : il restera sans doute joignable, et le compter comme perdu serait faux.
 * Les liens appris des alertes (règles d'association acceptées) sont rendus à part : ils disent «
 * tombent souvent ensemble », pas « est branché derrière ».
 *
 * <p>Périmètre (ADR 0018) : la cible hors périmètre est introuvable ; les équipements en aval hors
 * périmètre sont comptés mais pas nommés.
 */
@Service
public class ImpactService {
  private final ImpactRepository repository;
  private final SiteScope scope;
  private final Clock clock;

  public ImpactService(ImpactRepository repository, SiteScope scope, Clock clock) {
    this.repository = repository;
    this.scope = scope;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ImpactResponse analyse(UUID equipmentId) {
    EquipmentRow target =
        repository
            .equipment(equipmentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement introuvable"));
    scope.requireVisible(target.siteId(), "Équipement introuvable");
    Instant now = clock.instant();

    List<DownstreamRow> allDown = repository.downstream(equipmentId);
    Set<UUID> touched = new HashSet<>();
    touched.add(equipmentId);
    allDown.forEach(d -> touched.add(d.id()));

    // Second chemin : un amont qui n'est ni la cible ni un équipement touché.
    Map<UUID, List<UUID>> upstreams =
        repository.upstreamLinks(touched).stream()
            .collect(
                Collectors.groupingBy(
                    LinkRow::equipmentId,
                    Collectors.mapping(LinkRow::upstreamId, Collectors.toList())));
    Set<UUID> redundant = ImpactAssessment.redundant(touched, equipmentId, upstreams);

    List<DownstreamRow> visibleDown =
        allDown.stream().filter(d -> scope.allows(d.siteId())).toList();
    int hidden = allDown.size() - visibleDown.size();

    // Ce qui est réellement perdu : la cible, et l'aval sans second chemin.
    Set<UUID> lost = new HashSet<>();
    lost.add(equipmentId);
    allDown.stream().filter(d -> !redundant.contains(d.id())).forEach(d -> lost.add(d.id()));
    Set<UUID> lostVisible = new HashSet<>();
    lostVisible.add(equipmentId);
    visibleDown.stream()
        .filter(d -> !redundant.contains(d.id()))
        .forEach(d -> lostVisible.add(d.id()));

    List<ServiceLine> services =
        repository.services(lostVisible).stream()
            .map(s -> new ServiceLine(s.name(), s.type(), s.equipmentName()))
            .toList();
    List<Component> components =
        repository.statusComponents(lostVisible, target.siteId()).stream()
            .map(c -> new Component(c.name(), c.page(), c.publicPage()))
            .distinct()
            .toList();
    List<LearnedLink> learned =
        repository.acceptedLearnedLinks(equipmentId).stream()
            .filter(l -> scope.allows(l.siteId()))
            .map(l -> new LearnedLink(l.equipmentId(), l.name(), l.confidence()))
            .toList();
    int activeAlerts = repository.activeAlerts(lostVisible);
    String primary =
        target.siteId() == null
            ? null
            : repository.onCall(target.siteId(), "PRIMARY", now).orElse(null);
    String secondary =
        target.siteId() == null
            ? null
            : repository.onCall(target.siteId(), "SECONDARY", now).orElse(null);
    List<Maintenance> maintenances =
        repository.plannedMaintenances(equipmentId, target.siteId(), now).stream()
            .map(m -> new Maintenance(m.startAt(), m.endAt(), m.reason()))
            .toList();

    int lostDownstream = lost.size() - 1;
    long publicComponents = components.stream().filter(Component::publicPage).count();
    String level =
        ImpactAssessment.level(
            lostDownstream, services.size(), components.size(), publicComponents);

    List<Downstream> downstream =
        visibleDown.stream()
            .map(
                d ->
                    new Downstream(
                        d.id(),
                        d.name(),
                        d.type(),
                        d.status(),
                        d.siteName(),
                        d.depth(),
                        d.via(),
                        redundant.contains(d.id())))
            .toList();
    List<UUID> maintenanceTargets = new ArrayList<>();
    maintenanceTargets.add(equipmentId);
    visibleDown.stream()
        .filter(d -> !redundant.contains(d.id()))
        .forEach(d -> maintenanceTargets.add(d.id()));

    return new ImpactResponse(
        new Target(target.id(), target.name(), target.type(), target.status(), target.siteName()),
        level,
        ImpactAssessment.summary(
            target.name(),
            lostDownstream,
            redundant.size(),
            services.size(),
            components.size(),
            publicComponents),
        downstream,
        hidden,
        services,
        components,
        learned,
        activeAlerts,
        primary,
        secondary,
        maintenances,
        ImpactAssessment.recommendations(
            lostDownstream,
            components.stream().filter(Component::publicPage).map(Component::name).toList(),
            learned.stream().map(LearnedLink::equipmentName).toList(),
            primary,
            !maintenances.isEmpty(),
            activeAlerts),
        maintenanceTargets,
        now);
  }
}
