package sn.ept.transdic1.common.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Duration;

/** Stocke une durée en secondes dans une colonne BIGINT, identique quel que soit le moteur. */
@Converter
public class DurationSecondsConverter implements AttributeConverter<Duration, Long> {
  @Override
  public Long convertToDatabaseColumn(Duration value) {
    return value == null ? null : value.getSeconds();
  }

  @Override
  public Duration convertToEntityAttribute(Long seconds) {
    return seconds == null ? null : Duration.ofSeconds(seconds);
  }
}
