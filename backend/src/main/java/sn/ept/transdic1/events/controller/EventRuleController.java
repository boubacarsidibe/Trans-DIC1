package sn.ept.transdic1.events.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.events.dto.EventRuleRequest;
import sn.ept.transdic1.events.dto.EventRuleResponse;
import sn.ept.transdic1.events.dto.EventRuleTestDtos.EventRuleTestRequest;
import sn.ept.transdic1.events.dto.EventRuleTestDtos.EventRuleTestResponse;
import sn.ept.transdic1.events.service.EventRuleService;

@Tag(
    name = "Règles d'événements",
    description =
        "Règles qui transforment un trap ou un message syslog en alerte EVENT (ADR 0021). Lecture"
            + " pour tous les rôles, écriture et essai réservés aux administrateurs.")
@RestController
@RequestMapping("/api/v1/regles-evenements")
public class EventRuleController {
  private final EventRuleService service;

  public EventRuleController(EventRuleService service) {
    this.service = service;
  }

  @Operation(summary = "Lister les règles d'événements", description = "20 par page, par nom.")
  @GetMapping
  public Page<EventRuleResponse> list(
      @PageableDefault(size = 20, sort = "name") Pageable pageable) {
    return service.list(pageable);
  }

  @Operation(summary = "Consulter une règle d'événement")
  @ApiResponse(responseCode = "404", description = "Règle introuvable", content = @Content)
  @GetMapping("/{id}")
  public EventRuleResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @Operation(summary = "Créer une règle d'événement")
  @ApiResponse(responseCode = "400", description = "Règle invalide", content = @Content)
  @ApiResponse(responseCode = "409", description = "Nom déjà utilisé", content = @Content)
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public EventRuleResponse create(@Valid @RequestBody EventRuleRequest request) {
    return service.create(request);
  }

  @Operation(summary = "Remplacer une règle d'événement et sa portée")
  @ApiResponse(responseCode = "400", description = "Règle invalide", content = @Content)
  @ApiResponse(responseCode = "404", description = "Règle introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "Nom déjà utilisé", content = @Content)
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public EventRuleResponse update(
      @PathVariable UUID id, @Valid @RequestBody EventRuleRequest request) {
    return service.update(id, request);
  }

  @Operation(
      summary = "Supprimer une règle d'événement",
      description = "Refusé si la règle a déjà ouvert des alertes : désactivez-la.")
  @ApiResponse(responseCode = "404", description = "Règle introuvable", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "La règle a ouvert des alertes",
      content = @Content)
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }

  @Operation(
      summary = "Tester une règle sur un exemple",
      description =
          "Indique si l'événement d'exemple ouvrirait ou résoudrait une alerte. Rien n'est"
              + " enregistré : ni règle, ni événement, ni alerte.")
  @ApiResponse(responseCode = "400", description = "Règle ou exemple invalide", content = @Content)
  @PostMapping("/tester")
  @PreAuthorize("hasRole('ADMIN')")
  public EventRuleTestResponse test(@Valid @RequestBody EventRuleTestRequest request) {
    return service.test(request);
  }
}
