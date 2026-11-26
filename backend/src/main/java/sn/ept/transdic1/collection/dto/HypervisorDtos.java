package sn.ept.transdic1.collection.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.HypervisorKind;
import sn.ept.transdic1.inventory.domain.HypervisorResourceKind;
import sn.ept.transdic1.inventory.domain.HypervisorResourceStatus;
import sn.ept.transdic1.inventory.domain.PowerState;

/** Contrats des connecteurs d'hyperviseur (ADR 0055). */
public final class HypervisorDtos {
  private HypervisorDtos() {}

  /**
   * Création ou modification d'un connecteur. {@code referenceSecret} est une référence {@code
   * env:NOM}, jamais le secret du jeton.
   */
  public record ConnectorRequest(
      @NotBlank @Size(max = 120) String nom,
      @NotNull HypervisorKind type,
      @NotBlank @Size(max = 255) String urlApi,
      @NotBlank @Size(max = 160) String identifiantJeton,
      @NotBlank @Size(max = 80) @Pattern(regexp = "env:[A-Za-z_][A-Za-z0-9_]{0,63}")
          String referenceSecret,
      @Size(max = 20_000) String certificatAutorite,
      @Min(30) @Max(3600) int intervalleSecondes,
      @NotNull UUID siteId,
      boolean creerSondesIcmp,
      boolean actif) {}

  /**
   * Un connecteur tel qu'on peut le relire : la référence du secret, jamais sa valeur. Le
   * certificat de l'autorité, lui, est public : il est renvoyé pour pouvoir être relu et modifié.
   */
  public record ConnectorResponse(
      UUID id,
      String nom,
      HypervisorKind type,
      String urlApi,
      String identifiantJeton,
      String referenceSecret,
      String certificatAutorite,
      int intervalleSecondes,
      UUID siteId,
      String siteNom,
      boolean creerSondesIcmp,
      boolean actif,
      Instant dernierPassage,
      Instant dernierSucces,
      String derniereErreur,
      int echecsConsecutifs,
      long hotes,
      long vms,
      long conteneurs,
      long supprimees) {}

  /** Un hôte, une VM ou un conteneur vu par le connecteur. */
  public record ResourceResponse(
      UUID id,
      HypervisorResourceKind type,
      String nom,
      String identifiantExterne,
      String hote,
      PowerState etat,
      String adresse,
      HypervisorResourceStatus statut,
      UUID equipementId,
      String equipementNom,
      Instant vueLe,
      Instant supprimeeLe) {}

  /** Bilan d'un passage (planifié ou demandé à la main). */
  public record SyncReport(
      boolean reussi,
      int hotes,
      int invites,
      int equipementsCrees,
      int migrations,
      int supprimees,
      int alertesOuvertes,
      String erreur) {}

  /** Essai de connexion, sans rien créer. */
  public record TestResult(boolean reussi, String message, int hotes, int invites) {}
}
