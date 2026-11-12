package sn.ept.transdic1.collection.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import sn.ept.transdic1.collection.service.SnmpClient.Reading;

/**
 * Lecture de la table des voisins de LLDP-MIB (ADR 0034). Calcul pur : à partir des colonnes
 * parcourues en SNMP, rend ce que chaque port voit au bout du câble.
 *
 * <p>L'index d'une ligne de {@code lldpRemTable} tient en trois nombres : un repère de temps, le
 * numéro du port local et un numéro d'ordre. Seul le port local nous intéresse ; le repère de temps
 * change à chaque réapparition du voisin et ne doit surtout pas servir d'identité, sans quoi un
 * câble rebranché créerait un voisin de plus à chaque fois.
 *
 * <p>Les valeurs sont nettoyées : un équipement peut annoncer n'importe quoi, y compris des retours
 * à la ligne ou cinq cents caractères de description.
 */
public final class LldpTable {
  /** Racine de la table des voisins. */
  public static final String REM_TABLE = "1.0.8802.1.1.2.1.4.1.1";

  public static final String REM_CHASSIS_ID = REM_TABLE + ".5";
  public static final String REM_PORT_ID = REM_TABLE + ".7";
  public static final String REM_PORT_DESC = REM_TABLE + ".8";
  public static final String REM_SYS_NAME = REM_TABLE + ".9";
  public static final String REM_SYS_DESC = REM_TABLE + ".10";

  /** Table des adresses de gestion annoncées par les voisins. */
  public static final String REM_MAN_ADDR_TABLE = "1.0.8802.1.1.2.1.4.2.1";

  /** Nom des ports locaux, pour dire « sur Gi0/1 » plutôt que « sur le port 3 ». */
  public static final String LOC_PORT_ID = "1.0.8802.1.1.2.1.3.7.1.3";

  static final int MAX_TEXT = 255;
  static final int MAX_DESCRIPTION = 500;

  /** Un voisin vu sur un port local. */
  public record Neighbour(
      int localPortIndex,
      String localPort,
      String chassisId,
      String portId,
      String portDescription,
      String systemName,
      String systemDescription,
      String managementAddress) {}

  private LldpTable() {}

  /**
   * Rassemble les colonnes parcourues en voisins.
   *
   * @param chassis colonne {@code lldpRemChassisId}, qui donne les lignes existantes
   * @param localPortNames noms des ports locaux, par index
   * @param managementAddresses adresses de gestion déjà extraites, par index de ligne
   */
  public static List<Neighbour> parse(
      List<Reading> chassis,
      List<Reading> portIds,
      List<Reading> portDescriptions,
      List<Reading> systemNames,
      List<Reading> systemDescriptions,
      Map<Integer, String> localPortNames,
      Map<String, String> managementAddresses) {
    Map<String, String> ports = byRow(REM_PORT_ID, portIds);
    Map<String, String> descriptions = byRow(REM_PORT_DESC, portDescriptions);
    Map<String, String> names = byRow(REM_SYS_NAME, systemNames);
    Map<String, String> systems = byRow(REM_SYS_DESC, systemDescriptions);

    var neighbours = new ArrayList<Neighbour>();
    for (var entry : byRow(REM_CHASSIS_ID, chassis).entrySet()) {
      String row = entry.getKey();
      String chassisId = clean(entry.getValue(), 120);
      if (chassisId == null) {
        continue; // sans identité de châssis, la ligne ne vaut rien
      }
      Integer localPort = localPort(row);
      if (localPort == null) {
        continue;
      }
      neighbours.add(
          new Neighbour(
              localPort,
              localPortNames.get(localPort),
              chassisId,
              clean(ports.get(row), 120),
              clean(descriptions.get(row), MAX_TEXT),
              clean(names.get(row), MAX_TEXT),
              clean(systems.get(row), MAX_DESCRIPTION),
              clean(managementAddresses.get(row), 45)));
    }
    return List.copyOf(neighbours);
  }

  /**
   * Adresse de gestion d'un voisin, extraite de {@code lldpRemManAddrTable}.
   *
   * <p>L'index de cette table est copieux : repère de temps, port, numéro d'ordre, type d'adresse,
   * longueur, puis les octets de l'adresse eux-mêmes. L'adresse se lit donc dans l'index, pas dans
   * la valeur. Seules les adresses IPv4 (type 1) sont retenues : ce sont les seules que
   * l'inventaire porte.
   */
  public static Map<String, String> managementAddresses(List<Reading> readings) {
    var byRow = new HashMap<String, String>();
    for (Reading reading : readings) {
      String suffix = suffix(REM_MAN_ADDR_TABLE, reading.oid());
      if (suffix == null) {
        continue;
      }
      String[] parts = suffix.split("\\.");
      // colonne, repère, port, ordre, type, longueur, puis l'adresse
      if (parts.length < 7) {
        continue;
      }
      int type = parseInt(parts[4]);
      int length = parseInt(parts[5]);
      if (type != 1 || length != 4 || parts.length < 10) {
        continue;
      }
      String address = parts[6] + "." + parts[7] + "." + parts[8] + "." + parts[9];
      byRow.putIfAbsent(parts[1] + "." + parts[2] + "." + parts[3], address);
    }
    return Map.copyOf(byRow);
  }

  /** Noms des ports locaux, par index. */
  public static Map<Integer, String> localPorts(List<Reading> readings) {
    var names = new HashMap<Integer, String>();
    for (Reading reading : readings) {
      String suffix = suffix(LOC_PORT_ID, reading.oid());
      String value = clean(reading.text(), 120);
      if (suffix == null || value == null) {
        continue;
      }
      int index = parseInt(suffix);
      if (index > 0) {
        names.put(index, value);
      }
    }
    return Map.copyOf(names);
  }

  /** Index de ligne (repère de temps, port, ordre) vers la valeur textuelle de la colonne. */
  private static Map<String, String> byRow(String column, List<Reading> readings) {
    var byRow = new HashMap<String, String>();
    for (Reading reading : readings) {
      String suffix = suffix(column, reading.oid());
      if (suffix != null && reading.text() != null) {
        byRow.put(suffix, reading.text());
      }
    }
    return byRow;
  }

  /** Numéro du port local : deuxième nombre de l'index. */
  static Integer localPort(String row) {
    String[] parts = row.split("\\.");
    if (parts.length < 3) {
      return null;
    }
    int port = parseInt(parts[1]);
    return port > 0 ? port : null;
  }

  private static String suffix(String prefix, String oid) {
    if (oid == null || !oid.startsWith(prefix + ".")) {
      return null;
    }
    return oid.substring(prefix.length() + 1);
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (RuntimeException e) {
      return -1;
    }
  }

  /**
   * Texte annoncé par un voisin, rendu inoffensif : sur une seule ligne, sans caractères de
   * contrôle, tronqué. Vide devient nul.
   */
  static String clean(String value, int max) {
    if (value == null) {
      return null;
    }
    var cleaned = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      cleaned.append(c < ' ' || c == 127 ? ' ' : c);
    }
    String text = cleaned.toString().replaceAll(" {2,}", " ").trim();
    if (text.isEmpty()) {
      return null;
    }
    return text.length() > max ? text.substring(0, max) : text;
  }

  /** Forme comparable d'un nom d'équipement : sans domaine, en minuscules. */
  public static String shortName(String name) {
    if (name == null) {
      return null;
    }
    String text = name.trim().toLowerCase(Locale.ROOT);
    int dot = text.indexOf('.');
    return dot > 0 ? text.substring(0, dot) : text;
  }
}
