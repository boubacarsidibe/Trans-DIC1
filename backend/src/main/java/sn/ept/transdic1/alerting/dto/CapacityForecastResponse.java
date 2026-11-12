package sn.ept.transdic1.alerting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import sn.ept.transdic1.alerting.domain.ForecastStatus;

/** Prévision de saturation d'un indicateur d'un équipement (ADR 0020). */
public record CapacityForecastResponse(
    @Schema(description = "Indicateur, par exemple disk.usage") String key,
    @Schema(description = "Unité des mesures (%)") String unit,
    @Schema(description = "Valeur la plus récente (moyenne de la dernière heure mesurée)")
        Double current,
    @Schema(description = "Tendance en points par jour (pente de la droite)") Double slopePerDay,
    @Schema(description = "Coefficient de détermination r², de 0 à 1") Double r2,
    @Schema(description = "Jours avant d'atteindre la limite ; absent sans tendance à la hausse")
        Double daysToLimit,
    @Schema(description = "Date estimée de saturation ; absente sans tendance à la hausse")
        Instant projectedAt,
    ForecastStatus status,
    @Schema(description = "Confiance : haute (r² ≥ 0,8), moyenne (≥ 0,5) ou faible")
        String confidence,
    @Schema(description = "Limite visée (100 pour un pourcentage)") double limit,
    @Schema(description = "Fenêtre d'observation en jours") int windowDays,
    @Schema(description = "Période minimale de mesures, en jours, pour une prévision")
        int minSpanDays,
    @Schema(description = "Nombre de points (moyennes horaires) utilisés") int points,
    Instant firstPointAt,
    Instant lastPointAt,
    @Schema(description = "Méthode retenue : droite ou cycle journalier")
        sn.ept.transdic1.alerting.domain.ForecastMethod method,
    @Schema(description = "Heure locale du sommet du cycle, pour la méthode saisonnière")
        Integer seasonalPeakHour,
    @Schema(description = "Erreur moyenne de la droite sur les dernières 24 heures")
        Double linearError,
    @Schema(description = "Erreur moyenne du cycle journalier sur les dernières 24 heures")
        Double seasonalError) {}
