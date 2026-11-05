package sn.ept.transdic1.collection.dto;

/**
 * Résultat d'un test immédiat d'une sonde (bouton « Tester ») ; rien n'est enregistré. {@code
 * error} est un court message français fixe.
 */
public record ProbeTestResult(boolean up, Integer latencyMs, Integer tlsDaysLeft, String error) {}
