// Modèles SNMP : on choisit le type d'équipement, les OID se remplissent seuls. Ils viennent des MIB
// publiques (RFC 1213, 2790, 3805, 1628) et des MIB des constructeurs ; un OID absent d'un modèle
// d'équipement donné remonte une lecture en échec, sans bloquer les autres.

const metric = (key, name, oid, unit, valueType = "INTEGER") => ({ key, name, oid, unit, valueType });

const INTERFACE_1 = [
  metric("net.in.octets[1]", "Octets reçus (cumul), interface 1", "1.3.6.1.2.1.2.2.1.10.1", "o"),
  metric("net.out.octets[1]", "Octets émis (cumul), interface 1", "1.3.6.1.2.1.2.2.1.16.1", "o"),
  metric("net.errors.in[1]", "Erreurs reçues (cumul), interface 1", "1.3.6.1.2.1.2.2.1.14.1", "count"),
];

export const SNMP_TEMPLATES = [
  {
    id: "generic",
    label: "Équipement réseau générique (MIB-II)",
    metrics: [metric("net.interfaces", "Nombre d’interfaces", "1.3.6.1.2.1.2.1.0", "count"), ...INTERFACE_1],
  },
  {
    id: "host",
    label: "Serveur ou NAS (HOST-RESOURCES)",
    metrics: [
      metric("cpu.usage", "Charge processeur", "1.3.6.1.2.1.25.3.3.1.2.1", "%"),
      metric("system.processes", "Processus", "1.3.6.1.2.1.25.1.6.0", "count"),
      ...INTERFACE_1,
    ],
  },
  {
    id: "cisco",
    label: "Cisco IOS",
    metrics: [
      metric("cpu.usage", "Charge processeur (5 min)", "1.3.6.1.4.1.9.9.109.1.1.1.1.8.1", "%"),
      metric("memory.used", "Mémoire utilisée", "1.3.6.1.4.1.9.9.48.1.1.1.5.1", "B"),
      metric("memory.free", "Mémoire libre", "1.3.6.1.4.1.9.9.48.1.1.1.6.1", "B"),
      ...INTERFACE_1,
    ],
  },
  {
    id: "mikrotik",
    label: "MikroTik RouterOS",
    metrics: [
      metric("cpu.usage", "Charge processeur", "1.3.6.1.2.1.25.3.3.1.2.1", "%"),
      metric("temperature", "Température", "1.3.6.1.4.1.14988.1.1.3.10.0", "°C"),
      ...INTERFACE_1,
    ],
  },
  {
    id: "procurve",
    label: "HP / Aruba ProCurve",
    metrics: [metric("cpu.usage", "Charge processeur", "1.3.6.1.4.1.11.2.14.11.5.1.9.6.1.0", "%"), ...INTERFACE_1],
  },
  {
    id: "printer",
    label: "Imprimante (Printer-MIB)",
    metrics: [
      metric("printer.toner", "Niveau de consommable", "1.3.6.1.2.1.43.11.1.1.9.1.1", "count"),
      metric("printer.pages", "Pages imprimées", "1.3.6.1.2.1.43.10.2.1.4.1.1", "count"),
    ],
  },
  {
    id: "ups",
    label: "Onduleur (UPS-MIB)",
    metrics: [
      metric("ups.charge", "Charge de la batterie", "1.3.6.1.2.1.33.1.2.4.0", "%"),
      metric("ups.minutes", "Autonomie restante", "1.3.6.1.2.1.33.1.2.3.0", "min"),
      metric("ups.load", "Charge de sortie", "1.3.6.1.2.1.33.1.4.4.1.5.1", "%"),
      metric("ups.input", "Tension d’entrée", "1.3.6.1.2.1.33.1.3.3.1.3.1", "V"),
    ],
  },
];
