package sn.ept.transdic1.threat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Télémétrie de sécurité d'un poste envoyée par l'agent 2.3 (ADR 0058). Jamais d'argument de
 * commande, de contenu de fichier ni de mot de passe : des noms, des chemins et des empreintes. Une
 * section absente (lecture impossible sur la machine) est {@code null}, pas vide.
 */
public record EndpointReport(
    @NotBlank @Size(max = 16) String os,
    boolean partial,
    @Size(max = 400) List<@Valid Process> processes,
    @Size(max = 600) List<@Valid Persistence> persistence,
    @Size(max = 200) List<@Valid Account> accounts,
    @Size(max = 50) List<@Valid Integrity> integrity,
    @Size(max = 200) List<@Valid Connection> connections) {

  /** Un programme en cours : son nom et le chemin de son exécutable s'il est lisible. */
  public record Process(@NotBlank @Size(max = 64) String name, @Size(max = 300) String path) {}

  /**
   * Ce qui démarre seul : tâche cron, unité systemd, clé « Run », dossier de démarrage, service.
   */
  public record Persistence(
      @NotBlank @Pattern(regexp = "cron|rc|shell-init|systemd|run-key|startup|service") String kind,
      @NotBlank @Size(max = 300) String name,
      @Pattern(regexp = "[0-9a-f]{64}") String fingerprint,
      @Size(max = 300) String detail) {}

  /** Un compte ; {@code superuser} : UID 0 sous Linux. */
  public record Account(
      @NotBlank @Size(max = 128) String name, boolean privileged, boolean superuser) {}

  /** Un fichier sensible : présent ou non, et son empreinte s'il est lisible. */
  public record Integrity(
      @NotBlank @Size(max = 300) String path,
      boolean present,
      @Pattern(regexp = "[0-9a-f]{64}") String sha256) {}

  /** Une connexion sortante établie : l'adresse et le port distants. */
  public record Connection(
      @NotBlank @Size(max = 45) @Pattern(regexp = "[0-9A-Fa-f:.]+") String address,
      @Min(1) @Max(65535) int port) {}
}
