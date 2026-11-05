package sn.ept.transdic1.events.service;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Expressions régulières des règles d'événements, protégées contre le retour arrière catastrophique
 * (ReDoS, ADR 0021) :
 *
 * <ul>
 *   <li>longueur bornée à {@value #MAX_LENGTH} caractères, compilation vérifiée à l'enregistrement
 *       ;
 *   <li>recherche bornée : le texte est lu au travers d'une séquence qui compte les accès aux
 *       caractères et interrompt la recherche au-delà d'un budget ({@value #BUDGET} accès), quel
 *       que soit le motif ; le résultat est alors « interrompu », traité comme « pas de
 *       correspondance » et compté ;
 *   <li>à l'enregistrement, le motif est essayé sur des textes piégés : s'il dépasse déjà le
 *       budget, il est refusé comme trop coûteux.
 * </ul>
 */
public final class SafeRegex {
  public static final int MAX_LENGTH = 200;

  /** Accès aux caractères autorisés pour une recherche (quelques dizaines de millisecondes). */
  public static final long BUDGET = 10_000_000L;

  private static final String[] PROBES = {
    "a".repeat(64) + "!",
    "1".repeat(64) + "!",
    " ".repeat(64) + "!",
    "ab".repeat(32) + "!",
    ",".repeat(64) + "!"
  };

  public enum Outcome {
    MATCH,
    NO_MATCH,
    ABORTED
  }

  private SafeRegex() {}

  /** Compile, insensible à la casse ; message en français si le motif est refusé. */
  public static Pattern compile(String regex) {
    if (regex == null || regex.isBlank()) {
      throw new IllegalArgumentException("L'expression régulière est vide");
    }
    if (regex.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "L'expression régulière dépasse " + MAX_LENGTH + " caractères");
    }
    try {
      return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException(
          "Expression régulière invalide : " + e.getDescription(), e);
    }
  }

  /** Compile et refuse un motif qui dépasse déjà le budget sur les textes piégés. */
  public static Pattern validate(String regex) {
    Pattern pattern = compile(regex);
    for (String probe : PROBES) {
      if (find(pattern, probe) == Outcome.ABORTED) {
        throw new IllegalArgumentException(
            "Expression régulière trop coûteuse (retour arrière excessif) : simplifiez-la");
      }
    }
    return pattern;
  }

  public static Outcome find(Pattern pattern, CharSequence text) {
    return find(pattern, text, BUDGET);
  }

  public static Outcome find(Pattern pattern, CharSequence text, long budget) {
    try {
      return pattern.matcher(new BudgetedSequence(text, new long[] {budget})).find()
          ? Outcome.MATCH
          : Outcome.NO_MATCH;
    } catch (BudgetExceeded e) {
      return Outcome.ABORTED;
    }
  }

  private static final class BudgetExceeded extends RuntimeException {
    BudgetExceeded() {
      super(null, null, false, false);
    }
  }

  /** Séquence qui décompte chaque accès à un caractère sur un budget partagé. */
  private record BudgetedSequence(CharSequence text, long[] remaining) implements CharSequence {
    @Override
    public char charAt(int index) {
      if (--remaining[0] < 0) {
        throw new BudgetExceeded();
      }
      return text.charAt(index);
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return new BudgetedSequence(text.subSequence(start, end), remaining);
    }

    @Override
    public String toString() {
      return text.toString();
    }
  }
}
