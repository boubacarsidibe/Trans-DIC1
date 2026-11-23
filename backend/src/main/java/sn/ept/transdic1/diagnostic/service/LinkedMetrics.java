package sn.ept.transdic1.diagnostic.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRepository.SeriesPoint;

/**
 * Indicateurs qui <b>varient comme</b> la mesure en alerte (M11, ADR 0040), sur l'équipement et sur
 * ses voisins directs.
 *
 * <p>Le travail de cette classe est d'aligner des séries qui ne sont pas alignées — les mesures
 * n'arrivent pas au même instant selon l'indicateur —, de corréler chacune à la série de référence
 * par {@link RankCorrelation}, puis de ne garder que ce qui mérite d'être montré.
 *
 * <p><b>On corrèle les variations, pas les niveaux.</b> C'est la décision la plus importante de
 * cette classe, et elle vient d'une mesure sur l'instance de démonstration, pas d'un principe.
 * Corrélées sur leurs niveaux, quatorze séries d'indicateurs <i>indépendants</i> en donnaient
 * <b>sept</b> « liées » à la mesure en alerte, jusqu'à 0,90 de corrélation, avec des p-values
 * écrasantes. La raison est connue depuis Yule (1926) : une mesure réseau n'est pas un tirage au
 * sort, c'est une marche aléatoire, lente et continue. Deux marches aléatoires indépendantes
 * dérivent chacune de leur côté et se ressemblent longuement par pur hasard, et le test de hasard,
 * qui suppose des instants indépendants, s'en trouve ridiculement optimiste.
 *
 * <p>La différence d'une minute à la suivante, elle, n'a plus de mémoire : deux marches
 * indépendantes ont des variations indépendantes. Sur les mêmes quatorze séries, après ce seul
 * changement, <b>aucune</b> n'est retenue et la plus forte corrélation tombe de 0,90 à 0,54. Et
 * c'est aussi la bonne question à poser pour un diagnostic : non pas « ces deux indicateurs ont-ils
 * dérivé dans le même sens en deux heures », mais « ont-ils bougé ensemble, minute après minute, au
 * moment de l'alerte ».
 *
 * <p><b>Deux conditions, et elles ne disent pas la même chose.</b> Une corrélation doit être
 * improbable par hasard (sa p-value) <i>et</i> forte en valeur absolue. La première sans la seconde
 * remplirait l'écran : sur deux heures de mesures à la minute, une corrélation de 0,25 est
 * statistiquement indiscutable et n'apprend rien à personne. La seconde sans la première ferait
 * passer pour un lien ce qui n'est qu'une coïncidence sur dix points.
 *
 * <p><b>Et une correction pour le nombre d'essais.</b> Un équipement a facilement vingt
 * indicateurs, ses voisins autant : à chercher une corrélation dans cent séries au seuil habituel
 * de 5 %, on en trouve cinq par pur hasard, à chaque alerte. Le seuil est donc divisé par le nombre
 * de séries réellement éprouvées (correction de Bonferroni). Sans cela, cet écran aurait inventé
 * des pistes à chaque consultation, ce qui est exactement ce qu'un outil de diagnostic ne doit pas
 * faire.
 *
 * <p>Calcul pur, sans Spring ni base.
 */
public final class LinkedMetrics {

  private LinkedMetrics() {}

  /**
   * Réglages.
   *
   * @param minPoints instants communs minimaux entre les deux séries
   * @param minCorrelation corrélation minimale en valeur absolue, pour qu'elle vaille d'être lue
   * @param alpha risque accepté de prendre du hasard pour un lien, avant correction
   * @param maxShown nombre d'indicateurs rendus au plus
   * @param maxGap écart maximal entre deux instants consécutifs pour que leur variation compte :
   *     au-delà, on comparerait la variation d'une minute à celle d'une demi-heure
   */
  public record Settings(
      int minPoints, double minCorrelation, double alpha, int maxShown, Duration maxGap) {

    public static Settings defaults() {
      return new Settings(10, 0.7, 0.05, 5, Duration.ofMinutes(5));
    }
  }

  /** Un indicateur lié, avec de quoi l'afficher et de quoi juger le lien. */
  public record Linked(
      UUID equipmentId,
      String equipmentName,
      String key,
      String unit,
      double correlation,
      int points,
      double pValue,
      double before,
      double during) {}

  /**
   * Ce que l'examen a donné.
   *
   * @param linked indicateurs retenus, du lien le plus fort au plus faible
   * @param tested séries réellement éprouvées : c'est ce nombre qui a corrigé le seuil, et l'écran
   *     le dit, pour que le lecteur sache combien d'essais ont eu lieu avant qu'on lui montre ces
   *     quelques lignes
   */
  public record Outcome(List<Linked> linked, int tested) {

    public static Outcome empty() {
      return new Outcome(List.of(), 0);
    }
  }

  /** Une série : un équipement et un indicateur. */
  private record SeriesId(UUID equipmentId, String key) {}

  /** Ce qu'on retient d'une série avant de corréler. */
  private record Series(
      UUID equipmentId,
      String equipmentName,
      String key,
      String unit,
      TreeMap<Instant, Double> values) {}

  /**
   * Bornes du « avant » et du « pendant » affichés.
   *
   * <p>Ce sont <b>exactement</b> celles de {@code DiagnosticRepository.movedMetrics} : les deux
   * familles d'indicateurs se retrouvent dans la même liste à l'écran, et deux définitions
   * différentes de « avant » sous la même colonne rendraient les lignes incomparables.
   *
   * @param split fin du « avant » et début du « pendant »
   * @param end fin du « pendant »
   */
  public record Windows(Instant split, Instant end) {}

  /**
   * Indicateurs liés à {@code metricKey} de {@code equipmentId}, du lien le plus fort au plus
   * faible.
   *
   * @return liste vide si la série de référence est absente — sans elle il n'y a rien à corréler,
   *     ce qui est le cas des alertes qui ne portent sur aucun indicateur (perte de collecte,
   *     événement SNMP)
   */
  public static Outcome correlate(
      List<SeriesPoint> points,
      UUID equipmentId,
      String metricKey,
      Windows windows,
      Settings settings) {
    if (points == null || points.isEmpty() || metricKey == null || settings == null) {
      return Outcome.empty();
    }
    Map<SeriesId, Series> series = group(points);
    Series reference = series.get(new SeriesId(equipmentId, metricKey));
    if (reference == null) {
      return Outcome.empty();
    }
    // Toutes les corrélations sont calculées d'abord : la correction du seuil a besoin de savoir
    // combien d'essais ont réellement eu lieu, ce qu'on ne sait qu'à la fin.
    var tested = new ArrayList<Linked>();
    for (var entry : series.entrySet()) {
      if (entry.getKey().equals(new SeriesId(equipmentId, metricKey))) {
        continue; // la mesure en alerte, corrélée à elle-même : sans intérêt
      }
      Linked linked = compare(reference, entry.getValue(), windows, settings);
      if (linked != null) {
        tested.add(linked);
      }
    }
    if (tested.isEmpty()) {
      return Outcome.empty();
    }
    double threshold = settings.alpha() / tested.size();
    List<Linked> kept =
        tested.stream()
            .filter(
                linked ->
                    Math.abs(linked.correlation()) >= settings.minCorrelation()
                        && linked.pValue() <= threshold)
            .sorted(
                Comparator.comparingDouble((Linked linked) -> Math.abs(linked.correlation()))
                    .reversed()
                    .thenComparing(Linked::key))
            .limit(settings.maxShown())
            .toList();
    return new Outcome(kept, tested.size());
  }

  /**
   * Corrèle les <b>variations</b> de deux séries sur leurs instants communs ; null si le calcul n'a
   * pas de sens.
   *
   * <p>Les variations sont prises entre deux instants communs consécutifs, et seulement s'ils sont
   * assez proches. Deux indicateurs relevés à des cadences différentes restent donc comparables —
   * ce qui est le cas courant — tandis qu'un trou dans la collecte ne fabrique pas une variation
   * géante qui écraserait toutes les autres dans le classement des rangs.
   */
  private static Linked compare(
      Series reference, Series candidate, Windows windows, Settings settings) {
    var instants = new ArrayList<Instant>();
    var references = new ArrayList<Double>();
    var others = new ArrayList<Double>();
    for (var point : reference.values().entrySet()) {
      Double other = candidate.values().get(point.getKey());
      if (other != null) {
        instants.add(point.getKey());
        references.add(point.getValue());
        others.add(other);
      }
    }
    var left = new ArrayList<Double>();
    var right = new ArrayList<Double>();
    for (int i = 1; i < instants.size(); i++) {
      if (Duration.between(instants.get(i - 1), instants.get(i)).compareTo(settings.maxGap()) > 0) {
        continue;
      }
      left.add(references.get(i) - references.get(i - 1));
      right.add(others.get(i) - others.get(i - 1));
    }
    if (left.size() < settings.minPoints()) {
      return null;
    }
    RankCorrelation.Result result =
        RankCorrelation.between(toArray(left), toArray(right), settings.minPoints());
    if (result == null) {
      return null;
    }
    return new Linked(
        candidate.equipmentId(),
        candidate.equipmentName(),
        candidate.key(),
        candidate.unit(),
        MetricMovement.round(result.rho() * 100) / 100,
        result.points(),
        result.pValue(),
        MetricMovement.round(meanBefore(candidate, windows.split())),
        MetricMovement.round(meanDuring(candidate, windows.split(), windows.end())));
  }

  /**
   * Moyenne du « avant » : les points <b>strictement</b> antérieurs à la coupure. L'inclusion ou
   * l'exclusion de cette seule minute n'est pas un détail — c'est elle qui décide si la première
   * minute du nouveau régime est comptée dans l'ancien, et {@code movedMetrics} l'exclut.
   */
  private static double meanBefore(Series series, Instant split) {
    return mean(series, at -> at.isBefore(split));
  }

  /** Moyenne du « pendant » : de la coupure incluse à la fin incluse. */
  private static double meanDuring(Series series, Instant split, Instant end) {
    return mean(series, at -> !at.isBefore(split) && !at.isAfter(end));
  }

  private static double mean(Series series, java.util.function.Predicate<Instant> keep) {
    double total = 0;
    int count = 0;
    for (var entry : series.values().entrySet()) {
      if (keep.test(entry.getKey())) {
        total += entry.getValue();
        count++;
      }
    }
    if (count > 0) {
      return total / count;
    }
    // Pas un seul point de ce côté : on rend la moyenne de toute la série plutôt qu'un zéro, qui
    // se lirait comme une mesure à zéro.
    double all = 0;
    for (double value : series.values().values()) {
      all += value;
    }
    return series.values().isEmpty() ? 0 : all / series.values().size();
  }

  private static Map<SeriesId, Series> group(List<SeriesPoint> points) {
    var series = new LinkedHashMap<SeriesId, Series>();
    for (SeriesPoint point : points) {
      series
          .computeIfAbsent(
              new SeriesId(point.equipmentId(), point.key()),
              id ->
                  new Series(
                      point.equipmentId(),
                      point.equipmentName(),
                      point.key(),
                      point.unit(),
                      new TreeMap<>()))
          .values()
          .put(point.minute(), point.value());
    }
    return series;
  }

  private static double[] toArray(List<Double> values) {
    double[] array = new double[values.size()];
    for (int i = 0; i < array.length; i++) {
      array[i] = values.get(i);
    }
    return array;
  }
}
