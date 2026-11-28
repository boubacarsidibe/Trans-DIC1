package sn.ept.transdic1.collection.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Inventaire et top des processus envoyés par l'agent. Noms de processus seulement, jamais de ligne
 * de commande : elle contient souvent des mots de passe. Depuis l'agent 2.2 (ADR 0056), les
 * services système actifs et les ports TCP en écoute, absents d'un agent plus ancien ; depuis
 * l'agent 2.3 (ADR 0060), tous les services avec leur état et leur mode de démarrage.
 */
public record AgentSnapshotDto(
    @NotBlank @Size(max = 255) String hostname,
    @NotBlank @Size(max = 32) String os,
    @Size(max = 64) String osVersion,
    @Size(max = 32) String architecture,
    int cpuCount,
    long memoryTotal,
    boolean partial,
    @Valid Processes processes,
    @Size(max = 300) List<@NotBlank @Size(max = 128) String> services,
    @Size(max = 200) @Valid List<ListeningPort> listeningPorts,
    @Size(max = 300) @Valid List<ServiceState> serviceStates) {

  /** Compatibilité : un instantané sans états des services (agent 2.2 et avant). */
  public AgentSnapshotDto(
      String hostname,
      String os,
      String osVersion,
      String architecture,
      int cpuCount,
      long memoryTotal,
      boolean partial,
      Processes processes,
      List<String> services,
      List<ListeningPort> listeningPorts) {
    this(
        hostname,
        os,
        osVersion,
        architecture,
        cpuCount,
        memoryTotal,
        partial,
        processes,
        services,
        listeningPorts,
        null);
  }

  /**
   * Un service de la machine : démarré ou non, et mode de démarrage (AUTO, AUTO_DELAYED, MANUAL,
   * DISABLED ou UNKNOWN).
   */
  public record ServiceState(
      @NotBlank @Size(max = 128) String name,
      boolean active,
      @Pattern(regexp = "AUTO|AUTO_DELAYED|MANUAL|DISABLED|UNKNOWN") String startMode) {}

  public record Processes(
      @Size(max = 10) @Valid List<ProcessLine> byCpu,
      @Size(max = 10) @Valid List<ProcessLine> byMemory) {}

  /** Un port TCP en écoute : protocole (tcp ou tcp6), adresse locale, numéro. */
  public record ListeningPort(
      @NotBlank @Pattern(regexp = "tcp6?") String protocol,
      @Size(max = 45) String address,
      @Min(1) @Max(65535) int port) {}

  public record ProcessLine(
      @NotBlank @Size(max = 128) String name, long pid, double cpu, long memory) {}
}
