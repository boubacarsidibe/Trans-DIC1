package sn.ept.transdic1.threat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Lot de lignes de journal envoyé par l'agent d'un équipement. L'agent se contente de lire et de
 * découper : c'est le serveur qui juge, pour que la règle soit la même partout et modifiable sans
 * remettre à jour tous les agents.
 */
public record LogBatch(@NotEmpty @Size(max = 500) List<@Valid LogEntry> entries) {

  /**
   * Une ligne lue dans un journal.
   *
   * @param kind {@code AUTH_FAILURE}, {@code AUTH_SUCCESS} ou {@code WEB} ; le serveur décide
   *     ensuite si une requête web est une attaque, une erreur ou rien du tout
   * @param sourceAddress adresse de l'appelant, telle que le journal la donne
   * @param username compte visé par une tentative d'authentification
   * @param request chemin et paramètres de la requête web, sans le corps
   */
  public record LogEntry(
      @NotNull Instant occurredAt,
      @NotNull @Size(max = 24) String kind,
      @Size(max = 45) String sourceAddress,
      @Size(max = 120) String username,
      @Size(max = 10) String method,
      @Schema(description = "Chemin et paramètres, jamais le corps de la requête") @Size(max = 2000)
          String request,
      Integer status) {}
}
