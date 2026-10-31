package sn.ept.transdic1.inventory.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.dto.EquipmentImportDtos.ImportIssue;
import sn.ept.transdic1.inventory.dto.EquipmentImportDtos.ImportPreview;
import sn.ept.transdic1.inventory.dto.EquipmentImportDtos.ImportResult;
import sn.ept.transdic1.inventory.dto.EquipmentImportDtos.ImportRow;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Import CSV de l'inventaire (FEAT-07). Chaque ligne est validée ; l'aperçu sépare lignes valides,
 * erreurs et lignes ignorées sans rien créer. L'import refait la même validation côté serveur
 * (l'aperçu n'est jamais cru sur parole) et crée les lignes valides en une transaction. Les
 * doublons sont détectés par code d'inventaire et par adresse, dans le fichier et dans l'inventaire
 * existant.
 */
@Service
@Transactional
public class EquipmentImportService {
  /** Colonnes attendues, dans cet ordre ; séparateur « ; » (tableur français) ou « , ». */
  static final List<String> HEADER =
      List.of("code_inventaire", "nom", "adresse", "type", "statut", "site");

  static final int MAX_ROWS = 1000;
  private static final Pattern IPV4 =
      Pattern.compile(
          "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]{2,39}");
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");

  private final EquipmentRepository equipment;
  private final SiteRepository sites;
  private final AuditService audit;

  public EquipmentImportService(
      EquipmentRepository equipment, SiteRepository sites, AuditService audit) {
    this.equipment = equipment;
    this.sites = sites;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public ImportPreview preview(String csv) {
    return analyse(csv);
  }

  public ImportResult importCsv(String csv) {
    ImportPreview preview = analyse(csv);
    for (ImportRow row : preview.valid()) {
      Site site = sites.findByCode(row.siteCode()).orElseThrow();
      Equipment created =
          equipment.save(
              new Equipment(
                  row.inventoryCode(),
                  row.name(),
                  row.managementAddress(),
                  row.type(),
                  row.status(),
                  site));
      audit.record(
          "EQUIPMENT_CREATED",
          "EQUIPMENT",
          created.getId(),
          "code=" + created.getInventoryCode() + " type=" + created.getType() + " source=import");
    }
    audit.record(
        "EQUIPMENT_IMPORTED",
        "EQUIPMENT",
        null,
        "crees="
            + preview.valid().size()
            + " erreurs="
            + preview.errors().size()
            + " ignorees="
            + preview.ignored().size());
    return new ImportResult(preview.valid().size(), preview.errors(), preview.ignored());
  }

  private ImportPreview analyse(String csv) {
    String[] lines = (csv == null ? "" : csv).replace("﻿", "").split("\r?\n", -1);
    int headerIndex = firstContentLine(lines);
    if (headerIndex < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier vide");
    }
    char separator =
        lines[headerIndex].chars().filter(c -> c == ';').count()
                >= lines[headerIndex].chars().filter(c -> c == ',').count()
            ? ';'
            : ',';
    List<String> header =
        split(lines[headerIndex], separator).stream()
            .map(h -> h.trim().toLowerCase(Locale.ROOT))
            .toList();
    if (!header.equals(HEADER)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "En-tête attendu : " + String.join(";", HEADER));
    }
    long dataLines =
        java.util.Arrays.stream(lines, headerIndex + 1, lines.length)
            .filter(l -> !l.isBlank())
            .count();
    if (dataLines > MAX_ROWS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Fichier trop long : " + MAX_ROWS + " lignes au maximum");
    }

    List<ImportRow> candidates = new ArrayList<>();
    List<ImportIssue> errors = new ArrayList<>();
    List<ImportIssue> ignored = new ArrayList<>();
    Map<String, Site> siteCache = new HashMap<>();
    for (int i = headerIndex + 1; i < lines.length; i++) {
      int line = i + 1;
      String raw = lines[i];
      if (raw.isBlank()) {
        if (i < lines.length - 1) {
          ignored.add(new ImportIssue(line, "Ligne vide"));
        }
        continue;
      }
      if (raw.stripLeading().startsWith("#")) {
        ignored.add(new ImportIssue(line, "Commentaire"));
        continue;
      }
      List<String> cells;
      try {
        cells = split(raw, separator);
      } catch (IllegalArgumentException e) {
        errors.add(new ImportIssue(line, e.getMessage()));
        continue;
      }
      String problem = validate(cells, siteCache);
      if (problem != null) {
        errors.add(new ImportIssue(line, problem));
        continue;
      }
      candidates.add(
          new ImportRow(
              line,
              cells.get(0).trim(),
              cells.get(1).trim(),
              cells.get(2).trim(),
              EquipmentType.valueOf(cells.get(3).trim().toUpperCase(Locale.ROOT)),
              cells.get(4).isBlank()
                  ? EquipmentStatus.UNKNOWN
                  : EquipmentStatus.valueOf(cells.get(4).trim().toUpperCase(Locale.ROOT)),
              cells.get(5).trim()));
    }
    return new ImportPreview(withoutDuplicates(candidates, errors), errors, ignored);
  }

  /** Retire les doublons (dans le fichier puis avec l'inventaire) et les signale en erreur. */
  private List<ImportRow> withoutDuplicates(List<ImportRow> rows, List<ImportIssue> errors) {
    Set<String> existing = new HashSet<>();
    if (!rows.isEmpty()) {
      for (Object[] pair :
          equipment.findCodesAndAddresses(
              rows.stream().map(ImportRow::inventoryCode).toList(),
              rows.stream().map(ImportRow::managementAddress).toList())) {
        existing.add("code:" + pair[0]);
        existing.add("adresse:" + pair[1]);
      }
    }
    Map<String, Integer> seen = new HashMap<>();
    List<ImportRow> valid = new ArrayList<>();
    for (ImportRow row : rows) {
      String code = "code:" + row.inventoryCode();
      String address = "adresse:" + row.managementAddress();
      String message = null;
      if (existing.contains(code)) {
        message = "Doublon : code d'inventaire déjà présent dans l'inventaire";
      } else if (existing.contains(address)) {
        message = "Doublon : adresse déjà utilisée dans l'inventaire";
      } else if (seen.containsKey(code)) {
        message = "Doublon : même code d'inventaire qu'à la ligne " + seen.get(code);
      } else if (seen.containsKey(address)) {
        message = "Doublon : même adresse qu'à la ligne " + seen.get(address);
      }
      if (message != null) {
        errors.add(new ImportIssue(row.line(), message));
        continue;
      }
      seen.put(code, row.line());
      seen.put(address, row.line());
      valid.add(row);
    }
    errors.sort((a, b) -> Integer.compare(a.line(), b.line()));
    return valid;
  }

  private String validate(List<String> cells, Map<String, Site> siteCache) {
    if (cells.size() != HEADER.size()) {
      return HEADER.size() + " colonnes attendues, " + cells.size() + " trouvée(s)";
    }
    String code = cells.get(0).trim();
    String name = cells.get(1).trim();
    String address = cells.get(2).trim();
    if (!CODE.matcher(code).matches()) {
      return "Code d'inventaire invalide (lettres, chiffres, . _ / -, 64 caractères au plus)";
    }
    if (name.isEmpty() || name.length() > 120) {
      return "Nom obligatoire (120 caractères au plus)";
    }
    if (!isIpLiteral(address)) {
      return "Adresse IP invalide : " + abbreviate(address);
    }
    if (!isEnum(EquipmentType.class, cells.get(3))) {
      return "Type inconnu (" + names(EquipmentType.values()) + ")";
    }
    if (!cells.get(4).isBlank() && !isEnum(EquipmentStatus.class, cells.get(4))) {
      return "Statut inconnu (" + names(EquipmentStatus.values()) + ")";
    }
    String siteCode = cells.get(5).trim();
    Site site = siteCache.computeIfAbsent(siteCode, c -> sites.findByCode(c).orElse(null));
    if (site == null) {
      return "Site inconnu : " + abbreviate(siteCode);
    }
    return null;
  }

  /** Adresse IPv4 ou IPv6 littérale, sans aucune résolution DNS. */
  static boolean isIpLiteral(String value) {
    if (IPV4.matcher(value).matches()) {
      return true;
    }
    if (!value.contains(":") || !IPV6.matcher(value).matches()) {
      return false;
    }
    try {
      return java.net.InetAddress.getByName(value) instanceof java.net.Inet6Address;
    } catch (java.net.UnknownHostException e) {
      return false;
    }
  }

  private static <E extends Enum<E>> boolean isEnum(Class<E> type, String value) {
    try {
      Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String names(Enum<?>[] values) {
    return String.join(", ", java.util.Arrays.stream(values).map(Enum::name).toList());
  }

  private static String abbreviate(String value) {
    return value.length() > 40 ? value.substring(0, 40) + "…" : value;
  }

  private static int firstContentLine(String[] lines) {
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].isBlank()) {
        return i;
      }
    }
    return -1;
  }

  /** Découpe une ligne CSV ; un champ entre guillemets peut contenir le séparateur. */
  static List<String> split(String line, char separator) {
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          cell.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == separator) {
        cells.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    if (quoted) {
      throw new IllegalArgumentException("Guillemet non fermé");
    }
    cells.add(cell.toString());
    return cells;
  }
}
