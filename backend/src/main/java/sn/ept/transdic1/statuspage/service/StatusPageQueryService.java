package sn.ept.transdic1.statuspage.service;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageSummary;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageView;
import sn.ept.transdic1.statuspage.repository.StatusPageRepository;

/**
 * Lecture des pages de statut (ADR 0026). Vue interne : tout compte connecté, composants filtrés
 * par le périmètre de sites (ADR 0018). Vue publique : sans connexion, seulement pour une page
 * publique et active ; toute autre demande reçoit la même réponse « introuvable » (pas
 * d'énumération).
 */
@Service
public class StatusPageQueryService {
  /** Réponse unique d'une page publique inconnue, privée ou inactive. */
  public static final String PUBLIC_NOT_FOUND = "Page introuvable";

  private final StatusPageRepository pages;
  private final StatusSnapshotService snapshots;
  private final SiteScope scope;

  public StatusPageQueryService(
      StatusPageRepository pages, StatusSnapshotService snapshots, SiteScope scope) {
    this.pages = pages;
    this.snapshots = snapshots;
    this.scope = scope;
  }

  /** Pages lisibles par l'appelant : toutes pour un administrateur, les pages actives sinon. */
  public List<StatusPageSummary> list(boolean admin) {
    var list = admin ? pages.findAllByOrderByTitleAsc() : pages.findByActiveTrueOrderByTitleAsc();
    return list.stream()
        .map(
            p -> {
              var view = StatusSnapshotService.view(snapshots.get(p.getId()), scope::allows);
              return new StatusPageSummary(
                  p.getId(),
                  p.getSlug(),
                  p.getTitle(),
                  p.getDescription(),
                  p.getVisibility(),
                  p.isActive(),
                  view.overallState(),
                  view.components().size(),
                  p.getUpdatedAt());
            })
        .toList();
  }

  /** Vue interne d'une page (inactive : administrateurs seulement, en aperçu). */
  public StatusPageView internalView(UUID id, boolean admin) {
    var snapshot = snapshots.get(id);
    if (snapshot == null || (!snapshot.active() && !admin)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, StatusPageAdminService.PAGE_NOT_FOUND);
    }
    return StatusSnapshotService.view(snapshot, scope::allows);
  }

  /** Vue publique : tous les composants de la page, aucune donnée interne. */
  public StatusPageView publicView(String slug) {
    if (slug == null || !slug.matches("[a-z0-9-]{3,60}")) {
      throw notFound();
    }
    var id = snapshots.publicPageId(slug).orElseThrow(StatusPageQueryService::notFound);
    var snapshot = snapshots.get(id);
    if (snapshot == null || !snapshot.active() || snapshot.visibility() != PUBLIC) {
      throw notFound();
    }
    return StatusSnapshotService.view(snapshot, site -> true);
  }

  private static final sn.ept.transdic1.statuspage.domain.StatusVisibility PUBLIC =
      sn.ept.transdic1.statuspage.domain.StatusVisibility.PUBLIC;

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, PUBLIC_NOT_FOUND);
  }
}
