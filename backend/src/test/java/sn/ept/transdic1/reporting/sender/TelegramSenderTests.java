package sn.ept.transdic1.reporting.sender;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.reporting.port.NotificationSender.Context;

/** Boutons de prise en charge sur les messages Telegram (ADR 0051). */
class TelegramSenderTests {
  private final UUID alert = UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Test
  @DisplayName("Avec la prise en charge activée, une ouverture d'alerte porte les trois boutons")
  void buttonsOnAnOpening() {
    String json =
        new TelegramSender(null, true)
            .payload("Alerte", "Corps", new Context(Severity.CRITICAL, "ALERT_OPENED", alert));
    assertThat(json)
        .contains("\"reply_markup\":{\"inline_keyboard\"")
        .contains("\"callback_data\":\"take:" + alert + "\"")
        .contains("\"callback_data\":\"resolve:" + alert + "\"")
        .contains("\"callback_data\":\"diag:" + alert + "\"");
  }

  @Test
  @DisplayName("Pas de bouton sur une résolution, un rapport, ou quand la fonction est coupée")
  void noButtonsOtherwise() {
    assertThat(
            new TelegramSender(null, true)
                .payload("R", "C", new Context(Severity.CRITICAL, "ALERT_RESOLVED", alert)))
        .doesNotContain("reply_markup");
    assertThat(new TelegramSender(null, true).payload("R", "C", Context.unknown()))
        .doesNotContain("reply_markup");
    assertThat(
            new TelegramSender(null, false)
                .payload("A", "C", new Context(Severity.CRITICAL, "ALERT_OPENED", alert)))
        .doesNotContain("reply_markup")
        .startsWith("{\"text\":\"A\\nC\"");
  }
}
