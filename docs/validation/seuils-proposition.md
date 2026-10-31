# Proposition de seuils pour l'atelier avec le CRI (S4-SEUILS, #33)

Valeurs **de départ** à discuter. Elles s'appuient sur les pratiques courantes de supervision
(Zabbix, Nagios, recommandations des constructeurs). Elles ne remplacent pas la période
d'observation : on les ajuste ensuite sur les mesures réelles du campus.

## 1. Seuils proposés

Opérateur « > » : alerte quand la mesure dépasse le seuil pendant toute la durée de confirmation.
Le seuil de retour (hystérésis) évite qu'une alerte s'ouvre et se ferme en boucle.

| Règle | Métrique | Unité | Opérateur | Seuil | Retour | Confirmation | Sévérité | Justification |
|---|---|---|---|---|---|---|---|---|
| CPU élevé | `cpu.usage` | % | > | 85 | 75 | 10 min | WARNING | une charge brève est normale ; 10 min élimine les pics de sauvegarde ou de compilation |
| CPU saturé | `cpu.usage` | % | > | 95 | 85 | 5 min | CRITICAL | le service ralentit nettement au-delà |
| Mémoire élevée | `memory.usage` | % | > | 90 | 80 | 10 min | WARNING | Linux utilise la mémoire libre en cache : on vise la mémoire réellement prise |
| Mémoire saturée | `memory.usage` | % | > | 97 | 90 | 5 min | CRITICAL | risque de terminaison de processus par le noyau |
| Disque à surveiller | `disk.usage` | % | > | 80 | 75 | 15 min | WARNING | laisse le temps de nettoyer ou d'agrandir |
| Disque presque plein | `disk.usage` | % | > | 90 | 85 | 5 min | CRITICAL | bases et journaux s'arrêtent quand le disque est plein |
| Latence élevée | `latency` | ms | > | 100 | 60 | 5 min | WARNING | réseau de campus : quelques ms attendues ; 100 ms signale une congestion |
| Latence critique | `latency` | ms | > | 300 | 150 | 3 min | CRITICAL | applications interactives dégradées |
| Indisponibilité | `availability` | % | < | 100 | 100 | 2 cycles | CRITICAL | équipement injoignable (déjà couvert par l'alerte de collecte) |

Détection d'anomalies (ADR 0015) : sensibilité 3,5 (taux de fausses alertes mesuré 0,52 % sur
données de démonstration). À garder telle quelle pendant l'observation, puis à réévaluer.

## 2. Exceptions par équipement (à confirmer)

| Cas | Proposition |
|---|---|
| Serveur de calcul ou de sauvegarde, CPU haut la nuit | portée de la règle « CPU élevé » excluant l'équipement, ou maintenance récurrente sur la plage nocturne |
| Serveur de base de données qui garde la mémoire en cache | seuil mémoire WARNING à 95 % pour cet équipement |
| Partition d'archives volontairement pleine | règle disque limitée aux autres équipements |
| Liaison inter-sites lente par nature | seuils de latence propres au site concerné |

Trans-DIC1 gère ces exceptions par la portée des règles (site ou équipement) dans
**Administration > Règles d'alerte**. Chaque modification est tracée dans le journal d'audit.

## 3. Période d'observation

1. Mettre en place les seuils validés pendant **deux semaines**, avec notifications vers l'équipe
   projet uniquement (pas encore vers le NOC).
2. Chaque jour, noter les alertes : justifiée, fausse alerte, ou manquée (incident sans alerte).
3. Critère d'arrêt : moins de 5 % de fausses alertes par règle et aucun incident réel manqué.
4. Ajuster une règle à la fois, en consignant la raison.

Tableau de suivi :

| Règle | Alertes | Justifiées | Fausses | Manquées | Taux de fausses alertes | Ajustement proposé |
|---|---|---|---|---|---|---|
| CPU élevé | | | | | | |
| … | | | | | | |

## 4. Validation et traçabilité

Pour chaque seuil retenu : date, auteur de la modification, justification, nom de la personne du
CRI qui valide. Le journal d'audit de Trans-DIC1 garde l'auteur et la date ; la justification et la
validation sont reportées dans le procès-verbal de l'atelier.

## 5. Tester chaque seuil

Pour chaque règle, sur une cible de test autorisée : une mesure juste sous le seuil ne doit rien
ouvrir ; une mesure juste au-dessus, maintenue au-delà de la durée de confirmation, doit ouvrir une
seule alerte ; le retour sous le seuil de retour doit la résoudre.

## 6. Revenir aux valeurs précédentes

1. **Administration > Journal d'audit**, filtrer sur les règles d'alerte : chaque modification
   indique l'ancienne et la nouvelle valeur.
2. Remettre l'ancienne valeur dans **Administration > Règles d'alerte** (sans redémarrage).
3. Noter le retour arrière et sa raison dans le procès-verbal.
