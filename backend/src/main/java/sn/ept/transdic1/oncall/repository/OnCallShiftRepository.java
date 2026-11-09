package sn.ept.transdic1.oncall.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.oncall.domain.OnCallLevel;
import sn.ept.transdic1.oncall.domain.OnCallShift;

public interface OnCallShiftRepository extends JpaRepository<OnCallShift, UUID> {
  /** Gardes d'un niveau qui couvrent l'instant (début inclus, fin exclue) dans ces plannings. */
  @Query(
      "select s from OnCallShift s join fetch s.user join fetch s.schedule"
          + " where s.schedule.id in :schedules and s.level = :level"
          + " and s.startsAt <= :at and s.endsAt > :at")
  List<OnCallShift> findCovering(
      @Param("schedules") Collection<UUID> schedules,
      @Param("level") OnCallLevel level,
      @Param("at") Instant at);

  /** Gardes d'un planning qui chevauchent [from, to[. */
  @Query(
      "select s from OnCallShift s join fetch s.user where s.schedule.id = :schedule"
          + " and s.startsAt < :to and s.endsAt > :from order by s.startsAt, s.level, s.createdAt")
  List<OnCallShift> findOverlapping(
      @Param("schedule") UUID schedule, @Param("from") Instant from, @Param("to") Instant to);

  /** Gardes de plusieurs plannings qui chevauchent [from, to[ (agenda). */
  @Query(
      "select s from OnCallShift s join fetch s.user join fetch s.schedule"
          + " where s.schedule.id in :schedules and s.startsAt < :to and s.endsAt > :from"
          + " order by s.startsAt, s.level, s.createdAt")
  List<OnCallShift> findOverlappingIn(
      @Param("schedules") Collection<UUID> schedules,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /** Gardes d'une personne qui chevauchent [from, to[. */
  @Query(
      "select s from OnCallShift s join fetch s.schedule where s.user.id = :user"
          + " and s.startsAt < :to and s.endsAt > :from order by s.startsAt")
  List<OnCallShift> findForUser(
      @Param("user") UUID user, @Param("from") Instant from, @Param("to") Instant to);

  boolean existsByRotationIdAndStartsAt(UUID rotationId, Instant startsAt);

  /** Gardes d'une rotation qui chevauchent [from, to[. */
  @Query(
      "select s from OnCallShift s where s.rotation.id = :rotation"
          + " and s.startsAt < :to and s.endsAt > :from")
  List<OnCallShift> findRotationOverlapping(
      @Param("rotation") UUID rotation, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Retire les gardes futures générées par une rotation (jamais un remplacement, jamais une garde
   * commencée) : appelé quand la rotation change, avant de la régénérer.
   */
  @Modifying
  @Query(
      "delete from OnCallShift s where s.rotation.id = :rotation and s.override = false"
          + " and s.startsAt > :now")
  int deleteFutureGenerated(@Param("rotation") UUID rotation, @Param("now") Instant now);
}
