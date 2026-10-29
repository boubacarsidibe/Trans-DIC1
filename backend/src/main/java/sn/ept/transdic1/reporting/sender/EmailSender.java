package sn.ept.transdic1.reporting.sender;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.port.NotificationSender;

/** Adaptateur e-mail (SMTP). */
@Component
public class EmailSender implements NotificationSender {
  private final JavaMailSender mailSender;
  private final String from;

  public EmailSender(JavaMailSender mailSender, @Value("${notification.from}") String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.EMAIL;
  }

  @Override
  public void send(String destination, String subject, String body) throws Exception {
    MimeMessage mime = mailSender.createMimeMessage();
    var helper = new MimeMessageHelper(mime, false, "UTF-8");
    helper.setFrom(from);
    helper.setTo(destination);
    helper.setSubject(subject);
    helper.setText(body, false);
    mailSender.send(mime);
  }
}
