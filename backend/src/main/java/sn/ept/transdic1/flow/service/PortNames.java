package sn.ept.transdic1.flow.service;

import java.util.Map;

/**
 * Nom courant d'un service d'après son port et son protocole, pour que l'écran dise « HTTPS »
 * plutôt que « 443/6 ».
 *
 * <p>Liste volontairement courte : ce qu'on rencontre sur le réseau d'une école. Un port inconnu
 * est affiché tel quel, ce qui vaut mieux qu'un nom inventé.
 */
public final class PortNames {
  private static final Map<Integer, String> TCP =
      Map.ofEntries(
          Map.entry(20, "FTP"),
          Map.entry(21, "FTP"),
          Map.entry(22, "SSH"),
          Map.entry(23, "Telnet"),
          Map.entry(25, "SMTP"),
          Map.entry(80, "HTTP"),
          Map.entry(110, "POP3"),
          Map.entry(143, "IMAP"),
          Map.entry(389, "LDAP"),
          Map.entry(443, "HTTPS"),
          Map.entry(445, "Partage de fichiers (SMB)"),
          Map.entry(465, "SMTP"),
          Map.entry(587, "SMTP"),
          Map.entry(636, "LDAPS"),
          Map.entry(993, "IMAPS"),
          Map.entry(1433, "SQL Server"),
          Map.entry(3306, "MySQL"),
          Map.entry(3389, "Bureau à distance"),
          Map.entry(5432, "PostgreSQL"),
          Map.entry(8080, "HTTP"),
          Map.entry(8443, "HTTPS"));

  private static final Map<Integer, String> UDP =
      Map.of(
          53, "DNS",
          67, "DHCP",
          68, "DHCP",
          123, "NTP",
          161, "SNMP",
          162, "Trap SNMP",
          514, "Syslog",
          1514, "Syslog",
          2055, "NetFlow",
          5353, "mDNS");

  private PortNames() {}

  /** Nom du protocole IP : TCP, UDP, ICMP, ou son numéro. */
  public static String protocol(int protocol) {
    return switch (protocol) {
      case 1 -> "ICMP";
      case 2 -> "IGMP";
      case 6 -> "TCP";
      case 17 -> "UDP";
      case 47 -> "GRE";
      case 50 -> "ESP";
      case 58 -> "ICMPv6";
      case 89 -> "OSPF";
      case 112 -> "VRRP";
      default -> "Protocole " + protocol;
    };
  }

  /** Nom du service, ou null si le port n'évoque rien de connu. */
  public static String service(int protocol, int port) {
    if (protocol == 6) {
      return TCP.get(port);
    }
    if (protocol == 17) {
      return UDP.get(port);
    }
    return null;
  }

  /** Comment l'écran nomme un service : « HTTPS » ou « 8291/TCP ». */
  public static String label(int protocol, int port) {
    String service = service(protocol, port);
    if (service != null) {
      return service;
    }
    if (port <= 0) {
      return protocol(protocol);
    }
    return port + "/" + (protocol == 6 ? "TCP" : protocol == 17 ? "UDP" : protocol(protocol));
  }
}
