package sn.ept.transdic1.flow.receiver;

/**
 * Un flux lu dans un paquet NetFlow : une conversation observée par l'équipement qui exporte,
 * résumée en quelques champs.
 *
 * <p>Seul ce dont la supervision se sert est retenu. Les autres champs du format (prochain saut,
 * systèmes autonomes, masques, indicateurs TCP) sont lus puis abandonnés : les garder reviendrait à
 * conserver une copie du trafic de l'école sans savoir qu'en faire.
 *
 * @param protocol numéro de protocole IP (6 TCP, 17 UDP, 1 ICMP)
 * @param inputInterface index SNMP du port d'entrée, nul si l'exportateur ne le donne pas
 */
public record FlowRecord(
    String sourceAddress,
    String destinationAddress,
    int protocol,
    int sourcePort,
    int destinationPort,
    long bytes,
    long packets,
    Integer inputInterface,
    Integer outputInterface) {

  /** Vrai si le flux porte de quoi être compté : deux adresses et au moins un octet. */
  public boolean isUsable() {
    return sourceAddress != null
        && destinationAddress != null
        && !sourceAddress.isBlank()
        && !destinationAddress.isBlank()
        && bytes > 0;
  }
}
