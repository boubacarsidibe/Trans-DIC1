package sn.ept.transdic1.oncall.service;

import java.util.Comparator;
import sn.ept.transdic1.oncall.domain.OnCallRotation;
import sn.ept.transdic1.oncall.domain.OnCallSchedule;
import sn.ept.transdic1.oncall.domain.OnCallShift;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallPersonResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallRotationResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallScheduleResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallShiftResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallSiteRef;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallUserRef;
import sn.ept.transdic1.oncall.service.OnCallResolver.OnCallPerson;

/** Conversion des entités d'astreinte en réponses de l'API (aucune entité n'est exposée). */
final class OnCallMapper {
  private OnCallMapper() {}

  static OnCallScheduleResponse schedule(OnCallSchedule s) {
    return new OnCallScheduleResponse(
        s.getId(),
        s.getName(),
        s.getDescription(),
        s.getTimezone(),
        s.isAllSites(),
        s.getSites().stream()
            .sorted(Comparator.comparing(site -> site.getName()))
            .map(site -> new OnCallSiteRef(site.getId(), site.getCode(), site.getName()))
            .toList(),
        s.isActive(),
        s.getCreatedAt(),
        s.getUpdatedAt());
  }

  static OnCallShiftResponse shift(OnCallShift s) {
    return new OnCallShiftResponse(
        s.getId(),
        s.getSchedule().getId(),
        s.getSchedule().getName(),
        s.getUser().getId(),
        s.getUser().getUsername(),
        s.getLevel(),
        s.getStartsAt(),
        s.getEndsAt(),
        s.getNote(),
        s.isOverride(),
        s.getRotation() != null,
        s.getReplacesShiftId(),
        s.getCreatedAt());
  }

  static OnCallRotationResponse rotation(OnCallRotation r) {
    return new OnCallRotationResponse(
        r.getId(),
        r.getSchedule().getId(),
        r.getLevel(),
        r.getStartDate(),
        r.getShiftLengthDays(),
        r.getHandoverTime(),
        r.getMembers().stream().map(u -> new OnCallUserRef(u.getId(), u.getUsername())).toList(),
        r.getUpdatedAt());
  }

  static OnCallPersonResponse person(OnCallPerson p) {
    return p == null
        ? null
        : new OnCallPersonResponse(
            p.userId(),
            p.username(),
            p.shiftId(),
            p.scheduleId(),
            p.scheduleName(),
            p.endsAt(),
            p.override());
  }
}
