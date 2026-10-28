package sn.ept.transdic1.collection.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Lot de mesures envoyé par un agent (500 au maximum). */
public record AgentMeasuresRequest(@NotEmpty @Size(max = 500) List<@Valid MeasureInput> measures) {}
