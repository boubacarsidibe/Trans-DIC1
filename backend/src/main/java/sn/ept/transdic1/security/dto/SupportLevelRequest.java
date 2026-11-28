package sn.ept.transdic1.security.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Niveau de support d'un opérateur NOC (1 = N1, 2 = N2, 3 = N3) ; vide pour le retirer. */
public record SupportLevelRequest(@Min(1) @Max(3) Integer level) {}
