package sn.ept.transdic1.reporting.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.domain.NotificationPreference;
import sn.ept.transdic1.reporting.dto.PreferenceRequest;
import sn.ept.transdic1.reporting.dto.PreferenceResponse;
import sn.ept.transdic1.reporting.repository.NotificationPreferenceRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.repository.UserRepository;

/** Préférence de notification de chaque utilisateur : inactive tant qu'il ne l'a pas choisie. */
@Service
public class PreferenceService {
  private final NotificationPreferenceRepository preferences;
  private final UserRepository users;

  public PreferenceService(NotificationPreferenceRepository preferences, UserRepository users) {
    this.preferences = preferences;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public PreferenceResponse get(String username) {
    AppUser user = user(username);
    return preferences
        .findByUserId(user.getId())
        .map(p -> response(p, user))
        .orElseGet(
            () ->
                new PreferenceResponse(
                    NotificationChannel.EMAIL, Severity.CRITICAL, false, user.getEmail(), null));
  }

  @Transactional
  public PreferenceResponse save(String username, PreferenceRequest request) {
    AppUser user = user(username);
    String destination = request.destination() == null ? null : request.destination().trim();
    if (request.channel() == NotificationChannel.EMAIL) {
      destination = null;
    } else if (!SecretResolver.isSupported(destination)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Indiquez la référence du secret qui contient l'URL du canal (ex. env:TEAMS_NOC_URL)");
    }
    var preference =
        preferences
            .findByUserId(user.getId())
            .orElseGet(
                () ->
                    new NotificationPreference(user, request.channel(), request.minimumSeverity()));
    preference.setChannel(request.channel());
    preference.setMinimumSeverity(request.minimumSeverity());
    preference.setActive(request.active());
    preference.setDestination(destination);
    preferences.save(preference);
    return response(preference, user);
  }

  private static PreferenceResponse response(NotificationPreference p, AppUser user) {
    return new PreferenceResponse(
        p.getChannel(), p.getMinimumSeverity(), p.isActive(), user.getEmail(), p.getDestination());
  }

  private AppUser user(String username) {
    return users
        .findByUsername(username)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur inconnu"));
  }
}
