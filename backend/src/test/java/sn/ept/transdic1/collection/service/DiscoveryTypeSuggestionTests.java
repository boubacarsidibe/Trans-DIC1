package sn.ept.transdic1.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sn.ept.transdic1.inventory.domain.EquipmentType;

/** Type proposé d'après sysDescr ; l'utilisateur le confirme toujours avant l'import. */
class DiscoveryTypeSuggestionTests {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Cisco Aironet 2700 Series access point | WIFI_ACCESS_POINT",
        "Ubiquiti wireless controller | WIFI_ACCESS_POINT",
        "Aironet fictif | WIFI_ACCESS_POINT",
        "HP ProCurve J9019B | SWITCH",
        "Cisco Catalyst 2960 | SWITCH",
        "Generic managed switch | SWITCH",
        "MikroTik RouterOS 7.12 | ROUTER",
        "Cisco IOS Software, C1100 | ROUTER",
        "Edge router fictif | ROUTER",
        "VMware ESXi 8.0 | VIRTUAL_MACHINE",
        "QEMU KVM guest | VIRTUAL_MACHINE",
        "Virtual appliance | VIRTUAL_MACHINE",
        "Linux srv-web 6.1.0 x86_64 | SERVER",
      })
  void proposeUnTypeDApresLaDescription(String descr, EquipmentType expected) {
    assertThat(DiscoveryService.suggestType(descr)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      value = {"''"},
      nullValues = "null")
  void uneDescriptionVideDonneServeur(String descr) {
    assertThat(DiscoveryService.suggestType(descr)).isEqualTo(EquipmentType.SERVER);
    assertThat(DiscoveryService.suggestType(null)).isEqualTo(EquipmentType.SERVER);
  }
}
