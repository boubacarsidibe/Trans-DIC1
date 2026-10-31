package sn.ept.transdic1.collection.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.ept.transdic1.collection.dto.DiscoveryDtos.DiscoveryCandidateResponse;
import sn.ept.transdic1.collection.dto.DiscoveryDtos.DiscoveryScanResponse;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.SnmpVersion;

/** Recherches de découverte et équipements trouvés (tables discovery_scan, discovery_candidate). */
@Repository
public class DiscoveryRepository {
  private static final String SCAN_COLUMNS =
      "id, cidr, snmp_version, status, requested_by, hosts_total, hosts_scanned, error,"
          + " started_at, finished_at";

  private final JdbcTemplate jdbc;

  public DiscoveryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertScan(
      UUID id,
      String cidr,
      SnmpVersion version,
      String credentialRef,
      String identity,
      String requestedBy,
      int hostsTotal,
      Instant startedAt) {
    jdbc.update(
        "INSERT INTO discovery_scan(id, cidr, snmp_version, credential_ref, security_identity,"
            + " status, requested_by, hosts_total, started_at) VALUES (?, ?, ?, ?, ?, 'RUNNING', ?,"
            + " ?, ?)",
        id,
        cidr,
        version.name(),
        credentialRef,
        identity,
        requestedBy,
        hostsTotal,
        Timestamp.from(startedAt));
  }

  public void progress(UUID id, int scanned) {
    jdbc.update("UPDATE discovery_scan SET hosts_scanned = ? WHERE id = ?", scanned, id);
  }

  public void finish(UUID id, String status, int scanned, String error, Instant at) {
    jdbc.update(
        "UPDATE discovery_scan SET status = ?, hosts_scanned = ?, error = ?, finished_at = ?"
            + " WHERE id = ?",
        status,
        scanned,
        error,
        Timestamp.from(at),
        id);
  }

  /** Recherches restées « en cours » après un arrêt du serveur. Renvoie leur nombre. */
  public int interruptRunning(Instant at) {
    return jdbc.update(
        "UPDATE discovery_scan SET status = 'INTERRUPTED', error = 'Serveur arrêté pendant la"
            + " recherche', finished_at = ? WHERE status = 'RUNNING'",
        Timestamp.from(at));
  }

  public boolean anyRunning() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM discovery_scan WHERE status = 'RUNNING'", Integer.class);
    return count != null && count > 0;
  }

  public void insertCandidate(
      UUID scanId,
      String address,
      String sysName,
      String sysDescr,
      String sysObjectId,
      EquipmentType type,
      UUID existing,
      Instant at) {
    jdbc.update(
        "INSERT INTO discovery_candidate(id, scan_id, address, sys_name, sys_descr, sys_object_id,"
            + " suggested_type, existing_equipment_id, discovered_at) VALUES (?, ?, ?, ?, ?, ?, ?,"
            + " ?, ?)",
        UUID.randomUUID(),
        scanId,
        address,
        sysName,
        sysDescr,
        sysObjectId,
        type.name(),
        existing,
        Timestamp.from(at));
  }

  public Optional<DiscoveryScanResponse> find(UUID id) {
    List<DiscoveryScanResponse> found =
        jdbc.query(
            "SELECT " + SCAN_COLUMNS + " FROM discovery_scan WHERE id = ?",
            (rs, i) -> scan(rs, candidates(id)),
            id);
    return found.stream().findFirst();
  }

  /** Dernières recherches, sans le détail des équipements trouvés. */
  public List<DiscoveryScanResponse> recent(int limit) {
    return jdbc.query(
        "SELECT " + SCAN_COLUMNS + " FROM discovery_scan ORDER BY started_at DESC LIMIT ?",
        (rs, i) -> scan(rs, List.of()),
        limit);
  }

  private List<DiscoveryCandidateResponse> candidates(UUID scanId) {
    return jdbc.query(
        "SELECT address, sys_name, sys_descr, suggested_type, existing_equipment_id"
            + " FROM discovery_candidate WHERE scan_id = ? ORDER BY inet(address)",
        (rs, i) ->
            new DiscoveryCandidateResponse(
                rs.getString("address"),
                rs.getString("sys_name"),
                rs.getString("sys_descr"),
                EquipmentType.valueOf(rs.getString("suggested_type")),
                rs.getObject("existing_equipment_id", UUID.class)),
        scanId);
  }

  private static DiscoveryScanResponse scan(ResultSet rs, List<DiscoveryCandidateResponse> found)
      throws SQLException {
    Timestamp finished = rs.getTimestamp("finished_at");
    return new DiscoveryScanResponse(
        rs.getObject("id", UUID.class),
        rs.getString("cidr"),
        SnmpVersion.valueOf(rs.getString("snmp_version")),
        rs.getString("status"),
        rs.getString("requested_by"),
        rs.getInt("hosts_total"),
        rs.getInt("hosts_scanned"),
        rs.getString("error"),
        rs.getTimestamp("started_at").toInstant(),
        finished == null ? null : finished.toInstant(),
        found);
  }
}
