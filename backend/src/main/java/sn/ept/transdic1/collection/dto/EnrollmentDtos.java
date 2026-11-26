package sn.ept.transdic1.collection.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.ApprovalMode;
import sn.ept.transdic1.inventory.domain.EnrollmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;

/** Contrats de l'inscription automatique des agents (ADR 0054). */
public final class EnrollmentDtos {
  private EnrollmentDtos() {}

  /**
   * Jeton d'inscription authentifié : il n'ouvre que {@code POST /api/v1/agent/enroll}. Le nom («
   * inscription:… ») est celui que l'audit retient comme auteur.
   */
  public record EnrollmentPrincipal(UUID tokenId) implements Principal {
    @Override
    public String getName() {
      return "inscription:" + tokenId.toString().substring(0, 8);
    }
  }

  /**
   * Ce qu'un agent déclare de sa machine. {@code machineId} est l'empreinte propre à Trans-DIC1 de
   * /etc/machine-id ou de MachineGuid, calculée par l'agent : l'identifiant brut ne voyage pas.
   */
  public record AgentEnrollRequest(
      @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9._-]+") String hostname,
      @NotBlank @Size(min = 16, max = 128) @Pattern(regexp = "[A-Fa-f0-9-]+") String machineId,
      @Size(max = 16)
          List<@NotBlank @Size(max = 45) @Pattern(regexp = "[0-9A-Fa-f:.]+") String> ipAddresses,
      @NotBlank @Size(max = 64) String os,
      @Size(max = 120) String osVersion,
      @Size(max = 32) String agentVersion) {}

  /**
   * Réponse à l'agent : {@code INSCRIT} avec son jeton personnel (affiché une seule fois), ou
   * {@code EN_ATTENTE} avec le délai avant de redemander, ou un refus.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AgentEnrollResponse(
      String statut,
      UUID equipementId,
      String identifiant,
      String jeton,
      Integer reessayerDansSecondes,
      String message) {}

  /** Création d'un jeton d'inscription par un administrateur. */
  public record EnrollmentTokenRequest(
      @NotBlank @Size(max = 120) String nom,
      @NotNull UUID siteId,
      @NotNull Instant expireLe,
      @Min(1) @Max(100_000) int utilisationsMax,
      @Size(max = 20) List<@NotBlank @Size(max = 49) String> plagesAutorisees,
      @NotNull ApprovalMode mode,
      @NotNull EquipmentType typeEquipement) {}

  /** Jeton créé : {@code jeton} n'est renvoyé qu'ici, une seule fois. */
  public record EnrollmentTokenCreated(UUID id, String nom, String jeton, Instant expireLe) {}

  /** Un jeton d'inscription tel qu'on peut le relire : jamais le jeton lui-même. */
  public record EnrollmentTokenResponse(
      UUID id,
      String nom,
      UUID siteId,
      String siteNom,
      Instant expireLe,
      int utilisationsMax,
      int utilisations,
      List<String> plagesAutorisees,
      ApprovalMode mode,
      EquipmentType typeEquipement,
      String etat,
      String creePar,
      Instant creeLe,
      Instant revoqueLe,
      Instant derniereUtilisation) {}

  /** Une machine inscrite ou en attente. */
  public record EnrollmentRequestResponse(
      UUID id,
      UUID siteId,
      String siteNom,
      String nomHote,
      String machine,
      List<String> adresses,
      String adresseSource,
      String systeme,
      String versionSysteme,
      String versionAgent,
      EnrollmentStatus statut,
      String jetonNom,
      UUID equipementId,
      String equipementNom,
      Instant demandeeLe,
      Instant vueLe,
      String decidePar,
      Instant decideLe,
      Instant inscriteLe) {}

  /** Approbation ou refus par lot. */
  public record EnrollmentDecisionRequest(@NotEmpty @Size(max = 500) List<@NotNull UUID> ids) {}

  /** Bilan d'une décision par lot : ce qui a été fait, et pourquoi le reste ne l'a pas été. */
  public record EnrollmentDecisionResult(int traitees, List<String> ignorees) {}
}
