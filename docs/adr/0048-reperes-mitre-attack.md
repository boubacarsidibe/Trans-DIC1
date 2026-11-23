# ADR 0048 : repères MITRE ATT&CK sur l'écran Sécurité

- Statut : accepté
- Date : 2026-11-23
- Termine le point S7 de `docs/analyse-ecarts-concurrents-et-ml.md` (section 5)
- Complète l'[ADR 0028](0028-supervision-securite.md)

## Contexte

L'écran Sécurité montrait les attaques, les adresses actives et les équipements visés, mais pas le
repère MITRE ATT&CK que le plan prévoyait pour S7 et que Wazuh affiche par règle. Sans lui, une
équipe de sécurité qui lit « injection SQL » doit traduire seule vers le vocabulaire qu'elle utilise
dans ses rapports et ses outils.

## Décision

Rattacher chaque scénario reconnu à une technique de la matrice ATT&CK Enterprise, sur l'écran et
dans le message des alertes de sécurité.

| Ce que la supervision voit | Technique |
| --- | --- |
| Injection SQL, script injecté, traversée de répertoire, injection de modèle | T1190 Exploit Public-Facing Application |
| Exécution de commande | T1190, et T1059 Command and Scripting Interpreter |
| Sondage de chemins connus ; rafale de requêtes en erreur | T1595.003 Wordlist Scanning |
| Échecs d'authentification répétés | T1110 Brute Force |
| Connexion réussie depuis une adresse jamais vue | T1078 Valid Accounts |
| Balayage d'adresses ou de ports dans les flux | T1046 Network Service Discovery |
| Inondation d'une destination | T1498 Network Denial of Service |

Les identifiants, noms et tactiques ont été relus sur les fiches du site du MITRE (ATT&CK
Enterprise v20.1), et non recopiés de mémoire : une technique mal numérotée serait pire que
l'absence de repère, puisqu'elle enverrait quelqu'un lire la fiche d'une autre technique.

### Un repère, pas une classification

L'écran le dit en toutes lettres. Une technique dit à quoi ressemble ce qui a été vu, pas qui attaque
ni avec quel outil.

### Ce qui n'a volontairement aucune technique

- **Le soupçon du modèle appris** (forme inhabituelle) : c'est une hypothèse. Lui coller une
  technique la ferait passer pour un fait.
- **Un écart de volume** : il dit que quelque chose a changé, pas quoi.
- **Une erreur web isolée** : ce n'est pas un balayage, c'est leur rafale qui l'est. L'alerte de
  rafale reçoit T1595.003, pas chaque ligne 404.
- **Une connexion réussie prise seule** : elle n'a rien d'hostile. Seule l'alerte « adresse jamais
  vue » la rattache à T1078.

Les `switch` sur la nature d'une ligne et sur le sujet d'une alerte sont exhaustifs : ajouter une
nature ou un sujet sans trancher fait échouer la compilation, au lieu de laisser un scénario sans
repère sans que personne l'ait décidé.

### La matrice se lit sur toute la période

Le panneau regroupe par nature et par famille en SQL, puis rattache chaque groupe à ses techniques.
Il ne se contente donc pas des dernières lignes affichées : une technique vue il y a vingt heures
reste visible même si mille requêtes banales l'ont suivie.

## Alternatives écartées

- **Stocker la technique dans `security_event`** : une migration et une réécriture de l'historique
  pour un champ qui se déduit de la nature et de la famille, et qui changerait de sens si la
  correspondance était corrigée. Calculée à la lecture, une correction s'applique à tout le passé.
- **Rattacher chaque famille à une tactique seulement** : trop vague pour qu'on trouve la
  documentation de la technique.
- **Couvrir toute la matrice** : la supervision ne voit que des journaux d'authentification et
  d'accès web et des flux réseau. Prétendre couvrir la persistance ou l'évasion serait faux.

## Conséquences

- **Aucune migration, aucun nouveau réglage.** Sans événement de sécurité, le panneau est vide.
- **Le message des alertes de sécurité gagne un suffixe** `[MITRE ATT&CK T1190]`. Il se met à jour
  avec le message à chaque passage ; une alerte ouverte avant cette version l'obtient au prochain
  événement.
- **La correspondance est un choix, donc discutable.** T1595.003 pour le sondage de chemins connus
  est le plus proche de ce que la signature reconnaît, sans être exact à coup sûr ; un spécialiste
  peut préférer T1595. La correction tient en une ligne dans `MitreAttack`.
- **Les liens ouvrent le site du MITRE** (`rel="noreferrer noopener"`) : aucune donnée de
  l'installation n'y est envoyée.
