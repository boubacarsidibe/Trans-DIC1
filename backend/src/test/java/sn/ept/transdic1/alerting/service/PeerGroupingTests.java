package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.service.PeerComparisonService.GroupKey;
import sn.ept.transdic1.alerting.service.PeerComparisonService.Reading;

/**
 * Formation des groupes de semblables (M4, ADR 0041) : par modèle quand il y en a assez, par type
 * pour tout le reste, et jamais deux fois le même équipement.
 */
class PeerGroupingTests {
  private static final UUID SITE = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final String KEY = "cpu.usage";

  private static Reading reading(String model, double value) {
    return new Reading(UUID.randomUUID(), SITE, "SWITCH", model, KEY, value);
  }

  private static List<Reading> readings(String model, int count) {
    var all = new ArrayList<Reading>();
    for (int i = 0; i < count; i++) {
      all.add(reading(model, 10 + i));
    }
    return all;
  }

  @Test
  @DisplayName("Quatre équipements du même modèle forment leur propre groupe")
  void fourEquipmentsOfTheSameModelFormTheirOwnGroup() {
    var groups = PeerComparisonService.groups(readings("Catalyst 2960", 4));

    assertThat(groups).hasSize(1);
    assertThat(groups.keySet()).extracting(GroupKey::model).containsExactly("Catalyst 2960");
  }

  @Test
  @DisplayName("Ce que le modèle apporte : le cœur de réseau ne tire plus la médiane des accès")
  void whatTheModelAddsThecoreNoLongerPullsTheaccessMedian() {
    // Huit commutateurs d'accès d'un modèle, et un routeur de cœur d'un autre : même type, même
    // site. Mélangés, le cœur se démarquerait en permanence ou masquerait les autres.
    var readings = new ArrayList<Reading>(readings("Catalyst 2960", 8));
    readings.add(reading("Catalyst 9500", 85));

    var groups = PeerComparisonService.groups(readings);

    assertThat(groups).hasSize(2);
    var access =
        groups.entrySet().stream()
            .filter(entry -> "Catalyst 2960".equals(entry.getKey().model()))
            .findFirst()
            .orElseThrow();
    assertThat(access.getValue()).hasSize(8);
    // Le cœur, seul de son modèle, retombe sur le groupe par type — où il est désormais seul aussi,
    // donc trop petit pour juger : mieux vaut ne rien dire que de le comparer à des accès.
    var byType =
        groups.entrySet().stream()
            .filter(entry -> entry.getKey().model() == null)
            .findFirst()
            .orElseThrow();
    assertThat(byType.getValue()).hasSize(1);
  }

  @Test
  @DisplayName("Un modèle trop peu répandu retombe sur le groupe par type")
  void amodelTooRareFallsBackOnThegroupByType() {
    // Trois d'un modèle, trois d'un autre : aucun des deux ne fait groupe, mais ensemble ils
    // forment six semblables, et une comparaison par type vaut mieux que pas de comparaison.
    var readings = new ArrayList<Reading>(readings("Modèle A", 3));
    readings.addAll(readings("Modèle B", 3));

    var groups = PeerComparisonService.groups(readings);

    assertThat(groups).hasSize(1);
    assertThat(groups.keySet()).extracting(GroupKey::model).containsExactly((String) null);
    assertThat(groups.values().iterator().next()).hasSize(6);
  }

  @Test
  @DisplayName("Un modèle inconnu ne fait jamais groupe, même en nombre")
  void anunknownModelNeverFormsAgroupEvenInNumbers() {
    var readings = new ArrayList<Reading>();
    for (int i = 0; i < 6; i++) {
      readings.add(reading(i % 2 == 0 ? null : "  ", 10 + i));
    }

    var groups = PeerComparisonService.groups(readings);

    assertThat(groups).hasSize(1);
    assertThat(groups.keySet()).extracting(GroupKey::model).containsExactly((String) null);
  }

  @Test
  @DisplayName("Aucun équipement n'est jugé deux fois")
  void noequipmentIsJudgedTwice() {
    var readings = new ArrayList<Reading>(readings("Catalyst 2960", 5));
    readings.addAll(readings("Modèle rare", 2));

    var groups = PeerComparisonService.groups(readings);

    long placed = groups.values().stream().mapToLong(List::size).sum();
    assertThat(placed).isEqualTo(readings.size());
    // Deux alertes pour un seul fait seraient le pire des deux mondes.
    assertThat(groups.values().stream().flatMap(List::stream).distinct().count())
        .isEqualTo(readings.size());
  }

  @Test
  @DisplayName("Le message dit à quoi l'équipement a été comparé")
  void themessageSaysWhatTheEquipmentWasComparedTo() {
    assertThat(new GroupKey(SITE, "SWITCH", "Catalyst 2960", KEY).peers())
        .isEqualTo("du modèle Catalyst 2960 sur ce site");
    assertThat(new GroupKey(SITE, "SWITCH", null, KEY).peers())
        .isEqualTo("du même type sur ce site");
  }

  @Test
  @DisplayName("Les modèles sont nettoyés de leurs espaces avant d'être comparés")
  void modelsAreTrimmedBeforeBeingCompared() {
    assertThat(PeerComparisonService.model("  Catalyst 2960 ")).isEqualTo("Catalyst 2960");
    assertThat(PeerComparisonService.model("")).isNull();
    assertThat(PeerComparisonService.model(null)).isNull();
  }

  @Test
  @DisplayName("Deux sites ne se mélangent pas, même pour un modèle identique")
  void twositesDoNotMixEvenForTheSameModel() {
    UUID other = UUID.fromString("44444444-4444-4444-4444-444444444444");
    var readings = new ArrayList<Reading>(readings("Catalyst 2960", 4));
    for (int i = 0; i < 4; i++) {
      readings.add(new Reading(UUID.randomUUID(), other, "SWITCH", "Catalyst 2960", KEY, 50 + i));
    }

    var groups = PeerComparisonService.groups(readings);

    assertThat(groups).hasSize(2);
    assertThat(groups.keySet()).extracting(GroupKey::siteId).containsExactlyInAnyOrder(SITE, other);
  }
}
