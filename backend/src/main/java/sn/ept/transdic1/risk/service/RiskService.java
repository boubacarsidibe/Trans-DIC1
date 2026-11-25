package sn.ept.transdic1.risk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.risk.dto.RiskDtos.EquipmentRisk;
import sn.ept.transdic1.risk.dto.RiskDtos.FeatureWeight;
import sn.ept.transdic1.risk.dto.RiskDtos.ModelCard;
import sn.ept.transdic1.risk.dto.RiskDtos.Reason;
import sn.ept.transdic1.risk.dto.RiskDtos.RiskOverview;
import sn.ept.transdic1.risk.repository.RiskRepository;
import sn.ept.transdic1.risk.repository.RiskRepository.AlertCount;
import sn.ept.transdic1.risk.repository.RiskRepository.EquipmentRow;
import sn.ept.transdic1.risk.repository.RiskRepository.MetricPeak;
import sn.ept.transdic1.risk.repository.RiskRepository.StoredModel;

/**
 * Risque de panne à 24 heures (ADR 0052) : un modèle appris sur l'historique de la plateforme dit,
 * pour chaque équipement, la probabilité qu'il subisse une panne (perte de collecte ou alerte
 * critique) dans les 24 heures, et <b>pourquoi</b>.
 *
 * <p><b>Le jeu d'apprentissage.</b> Une ligne par équipement et par journée des deux derniers mois
 * : ce qu'on savait ce jour-là (alertes, pics d'occupation, pertes de collecte récentes), et ce qui
 * s'est passé le lendemain (panne ou non). La plateforme apprend donc de son propre passé, sans
 * données extérieures.
 *
 * <p><b>Le modèle.</b> Une régression logistique ({@link LogisticModel}) : chaque variable a un
 * poids qui se lit comme un facteur de risque (« un disque plein multiplie le risque par 7 »). Un
 * opérateur peut vérifier et contester ; il ne pourrait pas le faire avec un réseau de neurones.
 *
 * <p><b>Le petit parc.</b> Une école a peu de pannes : un modèle appris sur trois pannes ne vaut
 * rien. Les poids appris sont donc tirés vers des poids initiaux raisonnés, d'autant plus fort que
 * les pannes observées sont rares (même règle que les poids du diagnostic, ADR 0045), et la fiche
 * du modèle le dit.
 *
 * <p><b>L'évaluation.</b> Le modèle est éprouvé sur la dernière semaine, qu'il n'a pas vue pendant
 * l'apprentissage (découpage dans le temps, jamais au hasard : on ne prédit pas le passé avec le
 * futur). L'AUC et le score de Brier sont affichés tels quels, même mauvais.
 */
@Service
public class RiskService {
  private static final ZoneId ZONE = ZoneId.of("Africa/Dakar");
  private static final double L2 = 0.01;
  private static final double LEARNING_RATE = 0.5;
  private static final int ITERATIONS = 3000;

  private final RiskRepository repository;
  private final SiteScope scope;
  private final ObjectMapper json;
  private final JobGuard guard;
  private final Clock clock;
  private final int historyDays;
  private final int evaluationDays;
  private final double shrinkage;

  public RiskService(
      RiskRepository repository,
      SiteScope scope,
      ObjectMapper json,
      JobGuard guard,
      Clock clock,
      @Value("${risk.history-days:60}") int historyDays,
      @Value("${risk.evaluation-days:7}") int evaluationDays,
      @Value("${risk.shrinkage:20}") double shrinkage) {
    this.repository = repository;
    this.scope = scope;
    this.json = json;
    this.guard = guard;
    this.clock = clock;
    this.historyDays = historyDays;
    this.evaluationDays = evaluationDays;
    this.shrinkage = shrinkage;
  }

  // ------------------------------------------------------------------ apprentissage

  @Scheduled(cron = "${risk.training-cron:0 20 3 * * *}", zone = "Africa/Dakar")
  public void nightlyTraining() {
    guard.run("risk.training", Duration.ofMinutes(30), this::train);
  }

  /** Une journée d'un équipement : ce qu'on savait, et s'il est tombé le lendemain. */
  record Sample(LocalDate day, double[] x, int y) {}

  @Transactional
  public ModelCard train() {
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZONE);
    LocalDate first = today.minusDays(historyDays);
    Instant start = first.minusDays(7).atStartOfDay(ZONE).toInstant();
    Instant end = today.atStartOfDay(ZONE).toInstant();

    Map<Key, DayFacts> days = new HashMap<>();
    for (AlertCount a : repository.dailyAlerts(start, end)) {
      days.computeIfAbsent(new Key(a.equipmentId(), a.day()), k -> new DayFacts()).add(a);
    }
    for (MetricPeak p : repository.dailyPeaks(first.atStartOfDay(ZONE).toInstant(), end)) {
      days.computeIfAbsent(new Key(p.equipmentId(), p.day()), k -> new DayFacts()).peak(p);
    }

    List<Sample> samples = new ArrayList<>();
    LocalDate lastSampleDay = today.minusDays(2); // son lendemain doit être une journée complète
    for (var entry : days.entrySet()) {
      LocalDate day = entry.getKey().day();
      if (day.isBefore(first) || day.isAfter(lastSampleDay)) {
        continue;
      }
      UUID equipment = entry.getKey().equipmentId();
      int failures7d = 0;
      for (int back = 0; back < 7; back++) {
        DayFacts previous = days.get(new Key(equipment, day.minusDays(back)));
        failures7d += previous == null ? 0 : previous.collection;
      }
      DayFacts next = days.get(new Key(equipment, day.plusDays(1)));
      int label = next != null && (next.collection > 0 || next.critical > 0) ? 1 : 0;
      samples.add(new Sample(day, RiskFeatures.vector(entry.getValue().facts(failures7d)), label));
    }
    // Ordre stable : le même historique donne le même modèle, au bit près.
    samples.sort(
        Comparator.comparing(Sample::day)
            .thenComparing(s -> java.util.Arrays.toString(s.x()))
            .thenComparingInt(Sample::y));

    double[] prior = RiskFeatures.priorWeights();
    LocalDate evaluationStart = lastSampleDay.minusDays(evaluationDays - 1L);
    List<Sample> trainPart =
        samples.stream().filter(s -> s.day().isBefore(evaluationStart)).toList();
    List<Sample> evalPart =
        samples.stream().filter(s -> !s.day().isBefore(evaluationStart)).toList();

    Double auc = null;
    Double brier = null;
    if (!trainPart.isEmpty() && !evalPart.isEmpty()) {
      double[] trial = fit(trainPart, prior);
      double[] probabilities =
          evalPart.stream().mapToDouble(s -> LogisticModel.probability(trial, s.x())).toArray();
      int[] labels = evalPart.stream().mapToInt(Sample::y).toArray();
      auc = LogisticModel.auc(probabilities, labels);
      brier = LogisticModel.brier(probabilities, labels);
    }
    double[] weights = fit(samples, prior);
    int positives = samples.stream().mapToInt(Sample::y).sum();
    String mode = positives == 0 ? "POIDS_INITIAUX" : positives < shrinkage ? "MELANGE" : "APPRIS";
    double baseRate = samples.isEmpty() ? 0 : positives / (double) samples.size();
    var stored =
        new StoredModel(
            now,
            samples.size(),
            positives,
            mode,
            auc == null ? null : round(auc, 4),
            brier == null ? null : round(brier, 6),
            round(baseRate, 6),
            evalPart.size(),
            serialize(weights));
    repository.save(stored);
    return card(stored);
  }

  private double[] fit(List<Sample> samples, double[] prior) {
    if (samples.isEmpty()) {
      return prior.clone();
    }
    int[] labels = samples.stream().mapToInt(Sample::y).toArray();
    int positives = java.util.Arrays.stream(labels).sum();
    if (positives == 0) {
      return prior.clone();
    }
    double[] learned =
        LogisticModel.train(
            samples.stream().map(Sample::x).toList(), labels, L2, LEARNING_RATE, ITERATIONS);
    return LogisticModel.blend(learned, prior, positives, shrinkage);
  }

  // ------------------------------------------------------------------ scores

  @Transactional
  public RiskOverview overview(int limit) {
    StoredModel model = repository.model().orElse(null);
    if (model == null) {
      train();
      model = repository.model().orElseThrow();
    }
    double[] weights = deserialize(model.weightsJson());
    Instant now = clock.instant();
    Instant dayAgo = now.minus(Duration.ofHours(24));

    Map<UUID, DayFacts> window = new HashMap<>();
    for (AlertCount a : repository.windowAlerts(dayAgo, now)) {
      window.computeIfAbsent(a.equipmentId(), k -> new DayFacts()).add(a);
    }
    for (MetricPeak p : repository.windowPeaks(dayAgo, now)) {
      window.computeIfAbsent(p.equipmentId(), k -> new DayFacts()).peak(p);
    }
    Map<UUID, Integer> failures = new HashMap<>();
    for (AlertCount a : repository.windowAlerts(now.minus(Duration.ofDays(7)), now)) {
      if ("COLLECTION".equals(a.type())) {
        failures.merge(a.equipmentId(), a.count(), Integer::sum);
      }
    }

    List<EquipmentRisk> risks = new ArrayList<>();
    for (EquipmentRow e : repository.equipments()) {
      if (!scope.allows(e.siteId())) {
        continue;
      }
      DayFacts facts = window.getOrDefault(e.id(), new DayFacts());
      RiskFeatures.Facts f = facts.facts(failures.getOrDefault(e.id(), 0));
      double[] x = RiskFeatures.vector(f);
      double probability = LogisticModel.probability(weights, x);
      risks.add(
          new EquipmentRisk(
              e.id(),
              e.name(),
              e.siteName(),
              round(probability, 4),
              level(probability),
              reasons(weights, x, f)));
    }
    risks.sort(
        Comparator.comparingDouble(EquipmentRisk::probability)
            .reversed()
            .thenComparing(EquipmentRisk::name));
    return new RiskOverview(card(model), risks.stream().limit(Math.max(1, limit)).toList(), now);
  }

  @Transactional
  public ModelCard model() {
    return repository.model().map(this::card).orElseGet(this::train);
  }

  static String level(double probability) {
    if (probability >= 0.5) {
      return "ELEVE";
    }
    return probability >= 0.2 ? "MOYEN" : "FAIBLE";
  }

  /** Les trois faits qui poussent le plus le risque vers le haut (poids × valeur). */
  static List<Reason> reasons(double[] weights, double[] x, RiskFeatures.Facts f) {
    List<Reason> reasons = new ArrayList<>();
    for (int j = 0; j < x.length; j++) {
      double contribution = weights[j + 1] * x[j];
      if (contribution > 0.05) {
        var feature = RiskFeatures.FEATURES.get(j);
        reasons.add(
            new Reason(
                feature.name(),
                feature.label(),
                RiskFeatures.describe(feature.name(), f),
                round(contribution, 3)));
      }
    }
    reasons.sort(Comparator.comparingDouble(Reason::contribution).reversed());
    return reasons.stream().limit(3).toList();
  }

  private ModelCard card(StoredModel m) {
    double[] weights = deserialize(m.weightsJson());
    double[] prior = RiskFeatures.priorWeights();
    List<FeatureWeight> features = new ArrayList<>();
    for (int j = 0; j < RiskFeatures.FEATURES.size(); j++) {
      var feature = RiskFeatures.FEATURES.get(j);
      features.add(
          new FeatureWeight(
              feature.name(),
              feature.label(),
              round(weights[j + 1], 3),
              prior[j + 1],
              round(Math.exp(weights[j + 1]), 2)));
    }
    return new ModelCard(
        m.trainedAt(),
        m.mode(),
        m.samples(),
        m.positives(),
        m.baseRate(),
        m.auc(),
        m.brier(),
        m.evaluationSamples(),
        round(weights[0], 3),
        features,
        "Régression logistique apprise chaque nuit sur "
            + historyDays
            + " jours d'historique : une ligne par équipement et par journée (alertes, pics"
            + " d'occupation, pertes de collecte des 7 jours), la cible étant une panne (perte de"
            + " collecte ou alerte critique) le lendemain. Poids appris tirés vers des poids"
            + " initiaux raisonnés tant que les pannes observées sont rares (k = "
            + (int) shrinkage
            + "). Évaluation sur les "
            + evaluationDays
            + " derniers jours, jamais vus à l'apprentissage. Le facteur de risque (oddsRatio)"
            + " dit par combien une variable multiplie la cote du risque quand elle passe de 0"
            + " à 1.");
  }

  private String serialize(double[] weights) {
    try {
      return json.writeValueAsString(Map.of("weights", weights));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private double[] deserialize(String text) {
    try {
      double[] weights = json.readValue(text, Weights.class).weights();
      double[] prior = RiskFeatures.priorWeights();
      // Un modèle enregistré avant l'ajout d'une variable reprend les poids initiaux.
      return weights != null && weights.length == prior.length ? weights : prior;
    } catch (JsonProcessingException e) {
      return RiskFeatures.priorWeights();
    }
  }

  record Weights(double[] weights) {}

  private record Key(UUID equipmentId, LocalDate day) {}

  /** Faits accumulés d'un équipement sur une journée ou une fenêtre. */
  private static final class DayFacts {
    int anomalies;
    int warnings;
    int forecasts;
    boolean flapping;
    int noData;
    int collection;
    int critical;
    Double cpu;
    Double memory;
    Double disk;

    void add(AlertCount a) {
      switch (a.type()) {
        case "ANOMALY", "PEER" -> anomalies += a.count();
        case "FORECAST" -> forecasts += a.count();
        case "NO_DATA" -> noData += a.count();
        case "COLLECTION" -> collection += a.count();
        default -> {}
      }
      if ("WARNING".equals(a.severity()) && !"COLLECTION".equals(a.type())) {
        warnings += a.count();
      }
      if ("CRITICAL".equals(a.severity())) {
        critical += a.count();
      }
      flapping |= a.flapping();
    }

    void peak(MetricPeak p) {
      switch (p.family()) {
        case "cpu" -> cpu = cpu == null ? p.max() : Math.max(cpu, p.max());
        case "memory" -> memory = memory == null ? p.max() : Math.max(memory, p.max());
        default -> disk = disk == null ? p.max() : Math.max(disk, p.max());
      }
    }

    RiskFeatures.Facts facts(int failures7d) {
      return new RiskFeatures.Facts(
          anomalies, warnings, forecasts, flapping, cpu, memory, disk, failures7d, noData);
    }
  }

  private static double round(double value, int decimals) {
    double factor = Math.pow(10, decimals);
    return Math.round(value * factor) / factor;
  }
}
