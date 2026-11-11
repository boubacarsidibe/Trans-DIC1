package sn.ept.transdic1.threat.service;

import java.util.HashSet;
import java.util.Set;

/**
 * Découpage d'une requête en caractéristiques pour le modèle appris (S4).
 *
 * <p>Le modèle ne regarde ni les mots-clés ni la liste des chemins connus : seulement des
 * <b>suites de trois caractères</b>. C'est ce qui lui permet de reconnaître une charge utile d'une
 * forme qu'aucune signature ne couvre : {@code ')--}, {@code ${j}, {@code ../} ou {@code <sc} ne
 * se rencontrent pas dans une URL ordinaire, quel que soit le nom de l'attaque.
 *
 * <p>Les suites sont rangées dans un nombre fixe de cases par une empreinte FNV-1a : le modèle
 * tient dans quelques milliers de nombres, sans dictionnaire à transporter. Deux suites peuvent
 * tomber dans la même case ; avec {@value #BUCKETS} cases pour quelques milliers de suites
 * distinctes, cela reste rare et le modèle le supporte.
 *
 * <p>Seule la <b>présence</b> d'une suite compte, pas son nombre : une URL très longue ne doit pas
 * peser plus lourd qu'une courte du simple fait de sa longueur.
 */
public final class UrlFeatures {
  /** Nombre de cases d'empreinte. Puissance de deux : le reste se calcule par masque. */
  public static final int BUCKETS = 2048;

  /** Longueur des suites de caractères examinées. */
  public static final int NGRAM = 3;

  /** Longueur de requête examinée : au-delà, la suite n'apprend plus rien de neuf. */
  static final int MAX_LENGTH = 1000;

  private static final int FNV_OFFSET = 0x811c9dc5;
  private static final int FNV_PRIME = 0x01000193;

  private UrlFeatures() {}

  /**
   * Cases occupées par une requête, sans répétition.
   *
   * <p>La requête est décodée et mise en minuscules comme pour les signatures, puis encadrée de
   * {@code ^} et {@code $} pour que le début et la fin comptent comme des indices à part entière
   * (une URL qui se termine par {@code --} n'est pas la même chose qu'un tiret au milieu).
   */
  public static Set<Integer> of(String request) {
    String text = WebAttackSignatures.normalize(request);
    if (text.isEmpty()) {
      return Set.of();
    }
    if (text.length() > MAX_LENGTH) {
      text = text.substring(0, MAX_LENGTH);
    }
    String padded = "^" + text + "$";
    var buckets = new HashSet<Integer>();
    for (int i = 0; i + NGRAM <= padded.length(); i++) {
      buckets.add(bucket(padded, i));
    }
    return buckets;
  }

  /** Empreinte FNV-1a de la suite qui commence à {@code start}, ramenée au nombre de cases. */
  private static int bucket(String text, int start) {
    int hash = FNV_OFFSET;
    for (int i = start; i < start + NGRAM; i++) {
      hash ^= text.charAt(i);
      hash *= FNV_PRIME;
    }
    return (hash & 0x7fffffff) % BUCKETS;
  }
}
