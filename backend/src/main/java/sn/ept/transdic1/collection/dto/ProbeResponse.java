package sn.ept.transdic1.collection.dto;

import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.ServiceType;

/** Sonde d'un équipement, avec son dernier résultat ; aucune donnée interne de planification. */
public record ProbeResponse(
    UUID id,
    String name,
    ServiceType type,
    Integer port,
    String accessPoint,
    int intervalSeconds,
    int timeoutMs,
    String expectedStatus,
    String expectedText,
    boolean active,
    Instant lastCheckedAt,
    Boolean lastUp,
    Integer lastLatencyMs,
    String lastError,
    int consecutiveFailures,
    @io.swagger.v3.oas.annotations.media.Schema(
            description =
                "Objectif de disponibilité sur 30 jours, entre 0,5 et 1 ; absent sans objectif",
            nullable = true)
        Double slaTarget,
    @io.swagger.v3.oas.annotations.media.Schema(
            description =
                "Part du budget d'indisponibilité dépensée sur 30 jours, en pourcentage ;"
                    + " au-delà de 100 l'objectif est manqué. Absent sans objectif ou sans assez"
                    + " de relevés.",
            nullable = true)
        Double budgetConsomme,
    @io.swagger.v3.oas.annotations.media.Schema(
            description = "Minutes d'indisponibilité encore permises sur la fenêtre",
            nullable = true)
        Long budgetRestantMinutes,
    @io.swagger.v3.oas.annotations.media.Schema(
            description =
                "Vitesse de consommation sur la dernière heure, rapportée à la vitesse qui ferait"
                    + " durer le budget un mois : 1 = soutenable, 14,4 = épuisé en deux jours",
            nullable = true)
        Double tauxConsommation) {}
