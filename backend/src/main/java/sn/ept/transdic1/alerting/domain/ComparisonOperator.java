package sn.ept.transdic1.alerting.domain;

import java.math.BigDecimal;

public enum ComparisonOperator {
  GREATER_THAN,
  GREATER_OR_EQUAL,
  LESS_THAN,
  LESS_OR_EQUAL,
  EQUAL;

  /** Vrai si « valeur opérateur seuil » est satisfait. */
  public boolean test(BigDecimal value, BigDecimal threshold) {
    int comparison = value.compareTo(threshold);
    return switch (this) {
      case GREATER_THAN -> comparison > 0;
      case GREATER_OR_EQUAL -> comparison >= 0;
      case LESS_THAN -> comparison < 0;
      case LESS_OR_EQUAL -> comparison <= 0;
      case EQUAL -> comparison == 0;
    };
  }
}
