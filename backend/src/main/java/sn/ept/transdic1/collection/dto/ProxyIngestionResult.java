package sn.ept.transdic1.collection.dto;

/**
 * Bilan d'un lot relayé par un proxy (ADR 0047).
 *
 * @param stockees mesures entrées dans l'historique
 * @param evaluees mesures assez récentes pour avoir été confrontées aux règles d'alerte
 * @param tropVieilles mesures hors de la fenêtre de rattrapage, écartées sans faire échouer le lot
 *     — sinon un proxy dont le tampon a débordé ne pourrait plus jamais le vider
 * @param horsSite mesures visant un équipement qui n'appartient pas au site du proxy : écartées, et
 *     c'est un signe à regarder, un jeton volé ne doit pas écrire sur tout le campus
 * @param rejeu vrai si ce lot avait déjà été reçu ; le bilan est alors celui du premier passage
 */
public record ProxyIngestionResult(
    int stockees,
    int evaluees,
    int tropVieilles,
    int horsSite,
    int alertesOuvertes,
    int alertesFermees,
    boolean rejeu) {}
