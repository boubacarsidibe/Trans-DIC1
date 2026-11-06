package sn.ept.transdic1.collection.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Inventaire et top des processus envoyés par l'agent. Noms de processus seulement, jamais de ligne
 * de commande : elle contient souvent des mots de passe.
 */
public record AgentSnapshotDto(
    @NotBlank @Size(max = 255) String hostname,
    @NotBlank @Size(max = 32) String os,
    @Size(max = 64) String osVersion,
    @Size(max = 32) String architecture,
    int cpuCount,
    long memoryTotal,
    boolean partial,
    @Valid Processes processes) {

  public record Processes(
      @Size(max = 10) @Valid List<ProcessLine> byCpu,
      @Size(max = 10) @Valid List<ProcessLine> byMemory) {}

  public record ProcessLine(
      @NotBlank @Size(max = 128) String name, long pid, double cpu, long memory) {}
}
