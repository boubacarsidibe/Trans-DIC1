package sn.ept.transdic1.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Nom d'une interface transformé en instance de clé de mesure ({@code net.in[Gi0/1]}). */
class InterfaceKeyTests {
  @Test
  void unNomSimpleEstGardeTelQuel() {
    assertThat(InterfaceCollectionService.instanceKey("Gi0/1")).isEqualTo("Gi0/1");
    assertThat(InterfaceCollectionService.instanceKey("eth0")).isEqualTo("eth0");
  }

  @Test
  void lesCaracteresQuiGeneraientLaCleSontRemplaces() {
    // Crochets et espaces casseraient la lecture de « famille[instance] ».
    assertThat(InterfaceCollectionService.instanceKey("Port [principal]"))
        .isEqualTo("Port_principal_");
    assertThat(InterfaceCollectionService.instanceKey("Liaison vers le CRI"))
        .isEqualTo("Liaison_vers_le_CRI");
  }

  @Test
  void unNomVideOuTropLongResteExploitable() {
    assertThat(InterfaceCollectionService.instanceKey("   ")).isEqualTo("sans-nom");
    assertThat(InterfaceCollectionService.instanceKey(null)).isEqualTo("sans-nom");
    // La clé entière doit tenir dans les 64 caractères acceptés par l'API de mesures.
    assertThat(InterfaceCollectionService.instanceKey("x".repeat(80))).hasSize(44);
  }
}
