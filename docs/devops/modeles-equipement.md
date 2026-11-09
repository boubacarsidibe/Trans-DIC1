# Écrire ou ajouter un modèle d'équipement

Décision et règles d'application : [ADR-0022](../adr/0022-modeles-equipement.md).

Un modèle est un fichier JSON sous `backend/src/main/resources/templates/equipment/`. Il est lu et validé **au démarrage** du backend : un fichier invalide arrête le démarrage avec un message de la forme

```
Modèle d'équipement invalide (fichier mon-modele.json) : modèle mon-modele : indicateur cpu.usage : OID numérique sans point initial attendu, reçu « .1.3.6.1… »
```

Aucune propriété ni variable d'environnement n'est nécessaire (`templates.equipment.location` existe pour les tests ; valeur par défaut `classpath*:templates/equipment/*.json`).

## Règles

- **Jamais de secret** : ni communauté, ni compte SNMPv3. Ils se donnent à l'application du modèle, par référence `env:NOM`.
- **Citer l'objet de MIB** de chaque OID dans `source` (`UCD-SNMP-MIB::ssCpuIdle.0`), et l'unité d'origine quand une conversion s'applique.
- **`verified` à `false`** tant que le modèle n'a pas été essayé sur un matériel réel de l'EPT (snmpwalk de chaque OID puis premières mesures cohérentes). Seuls les objets MIB-II standard justifient `true` sans essai.
- Augmenter **`version`** à chaque modification. Réappliquer une nouvelle version n'ajoute que les éléments nouveaux ; rien n'est modifié ni supprimé sur les équipements.
- Les seuils sont des **suggestions** : les règles sont créées inactives, le CRI les règle puis les active.

## Schéma de référence (exemple commenté)

Le JSON n'admet pas de commentaires : ceux-ci (`//`) sont à retirer. Un champ inconnu est refusé (faute de frappe).

```jsonc
{
  "id": "linux-net-snmp",            // minuscules, chiffres et tirets ; unique ; 64 caractères au plus
  "version": 1,                      // entier >= 1, augmenté à chaque modification
  "name": "Serveur Linux (net-snmp)",// 120 caractères au plus
  "vendor": "Linux",                 // 80 caractères au plus ; filtre « fabricant » du catalogue
  "category": "SERVER",              // ROUTER, SWITCH, ACCESS_POINT, SERVER, UPS, PRINTER, SERVICE, GENERIC
  "description": "…",                // en français : prérequis (snmpd.conf…), index à vérifier
  "verified": false,                 // obligatoire
  "sysDescrHints": ["Linux"],        // facultatif : fragments du sysDescr qui suggèrent ce modèle
  "metrics": [                       // 50 au plus ; peut être vide si le modèle a des sondes
    {
      "name": "Charge CPU",
      "technicalKey": "cpu.usage",   // [a-z0-9][a-z0-9._-]*, unique dans le modèle ; clé des mesures et des règles
      "valueType": "DECIMAL",        // INTEGER, DECIMAL ou BOOLEAN (TEXT refusé)
      "unit": "%",                   // 24 caractères au plus, facultatif
      "snmpOid": "1.3.6.1.4.1.2021.11.11.0", // numérique, sans point initial
      "source": "UCD-SNMP-MIB::ssCpuIdle.0 (inversé : 100 - inactivité)",
      "scale": null,                 // facultatif, > 0, 10 décimales au plus : brute × scale
      "offset": null,                // facultatif, 4 décimales au plus : + offset (après scale)
      "invertPercent": true          // facultatif : 100 - valeur (après scale et offset)
    }
  ],
  "rules": [                         // suggestions, créées limitées à l'équipement et inactives
    {
      "name": "CPU élevé",           // 80 caractères au plus (suffixé de « — code d'inventaire »)
      "metricKey": "cpu.usage",      // clé d'un indicateur du modèle, ou probe.up / probe.latency /
                                     // probe.tls.days_left si le modèle a des sondes
      "operator": "GREATER_THAN",    // GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL, EQUAL
      "threshold": 90,
      "recoveryThreshold": 80,       // facultatif ; du côté normal du seuil (hystérésis)
      "unit": "%",
      "confirmationSeconds": 300,    // 0 à 86400
      "severity": "WARNING"          // INFO, WARNING, CRITICAL
    }
  ],
  "probes": [                        // 50 au plus ; visent l'adresse de gestion
    { "name": "Ping", "type": "ICMP", "intervalSeconds": 60 },       // pas de port pour ICMP
    { "name": "SSH", "type": "TCP_PORT", "port": 22, "intervalSeconds": 120 } // port obligatoire en TCP
  ],                                 // types : TCP_PORT, HTTP, HTTPS, ICMP, TLS_CERT ; intervalle 30 à 86400 s (60 par défaut)
  "eventRules": [                    // suggestions seulement : jamais créées par l'application
    {
      "name": "Lien tombé (trap linkDown)",
      "description": "Trap standard linkDown (IF-MIB).",
      "oidPrefix": "1.3.6.1.6.3.1.1.5.3", // ou "messageRegex" (200 caractères au plus)
      "severity": "WARNING"
    }
  ]
}
```

## Ajouter un modèle

1. Relever les OID avec `snmpwalk -v2c -c <communauté> <hôte> <OID racine>` sur un équipement réel si possible ; noter l'objet de MIB et l'unité.
2. Créer `templates/equipment/<id>.json`, `verified` à `false` sauf MIB-II pur.
3. Lancer `.\mvnw.cmd test -Dtest=EquipmentTemplateCatalogTests` : chaque modèle livré doit se charger, OID numériques, clés uniques. Ajouter son identifiant à la liste du test `everyBuiltInTemplateIsValid`.
4. Appliquer le modèle sur un équipement de test (page Équipements, bouton « Modèle », Prévisualiser puis Confirmer) et contrôler les premières mesures dans l'historique.
5. Une fois validé à l'EPT, passer `verified` à `true` et augmenter `version`.

## Conversions courantes

| Origine | Conversion |
|---|---|
| TimeTicks (centièmes de seconde) → secondes | `"scale": 0.01` |
| TimeTicks → minutes | `"scale": 0.0001666667` |
| Kio → Mio | `"scale": 0.0009765625` |
| octets → Mo | `"scale": 0.000001` |
| dixièmes de degré → °C | `"scale": 0.1` |
| taux d'inactivité → charge | `"invertPercent": true` |

La colonne des mesures garde 4 décimales et 8 chiffres avant la virgule : convertir les compteurs en octets (au-delà de 99 999 999) pour rester dans la plage.
