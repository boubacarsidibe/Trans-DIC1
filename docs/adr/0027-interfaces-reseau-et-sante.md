# ADR 0027 : interfaces réseau par SNMP, qualité des sondes et score de santé

- Statut : accepté
- Date : 2026-11-11
- Lot : « réseau et apprentissage » (section 4 de `docs/analyse-ecarts-concurrents-et-ml.md`)

## Contexte

Le benchmark du 2026-11-06 relève quatre manques face à Zabbix, LibreNMS, PRTG et Checkmk :

1. aucune mesure par port réseau (débit, erreurs, occupation), alors que c'est le cœur du métier
   d'une supervision d'infrastructure ;
2. la sonde ICMP ne dit que « joignable » et « temps de réponse », sans perte ni gigue ;
3. la détection d'anomalies se laisse aveugler par un incident passé ;
4. aucun chiffre unique pour dire si un équipement ou un site va bien.

## Décision

### 1. Interfaces réseau (IF-MIB)

La découverte est un choix par configuration de collecte (`discover_interfaces`, inactive par
défaut) : un parc déjà en place ne voit pas ses mesures se multiplier sans décision.

Deux temps séparés, parce qu'ils n'ont pas le même coût :

| Temps | Quoi | Quand |
|---|---|---|
| Découverte | parcours des colonnes `ifName` (ou `ifDescr`), `ifAlias`, `ifType`, `ifHighSpeed`, `ifAdminStatus` | au premier passage, puis toutes les heures |
| Relevé | lecture directe des compteurs des interfaces connues, par paquets de 30 OID | à chaque passage (1 min par défaut) |

Un parcours de table coûte un aller-retour UDP par valeur : pour un commutateur de 48 ports, le
faire à chaque minute reviendrait à des centaines de requêtes. La découverte rare puis la lecture
directe ramènent le coût à quelques requêtes par passage.

Mesures déposées, avec le nom du port en instance comme l'agent pour les disques :
`net.in[Gi0/1]`, `net.out[...]` (octets/s), `net.errors[...]`, `net.discards[...]` (par seconde),
`net.usage[...]` (% de la capacité annoncée, sens le plus chargé), `net.up[...]` (1 ou 0). Les
règles par famille (`net.usage[*]`) s'y appliquent sans configuration ; deux règles livrées
inactives accompagnent la migration V38.

Écartés du suivi mais gardés à l'inventaire : le bouclage logiciel (`ifType` 24) et les ports
désactivés par l'administrateur.

**Compteurs cumulés.** IF-MIB ne donne que des compteurs depuis le démarrage : le débit vient de
l'écart entre deux relevés, gardé dans `equipment_interface`. Un premier passage ne produit donc
aucun débit. Un compteur qui recule est traité selon sa taille : sous 2^32 c'est un débordement
banal, qu'on rattrape ; au-dessus, un compteur 64 bits ne peut pas déborder (des dizaines d'années
à 100 Gb/s), donc c'est un redémarrage et aucune valeur n'est produite. Un écart de temps hors de
[10 s, 2 h] ne donne rien non plus.

### 2. Perte de paquets et gigue

La sonde ICMP envoie désormais quatre paquets par passage
(`collection.probe.icmp.packets`, borné à 10, et à 3 sous Windows qui attend une seconde entre
deux envois). Elle dépose `probe.loss` (%) et `probe.jitter` (ms, écart moyen entre deux réponses
successives), en plus de `probe.up` et `probe.latency`. La latence rendue est la moyenne des
réponses reçues. Une règle « Perte de paquets » livrée inactive accompagne la migration V36.

Seules les lignes de réponse, reconnues à leur temps, sont comptées : les lignes de synthèse de
`ping` changent de langue avec le système et ne sont pas analysées.

### 3. Normale robuste des anomalies

Avant de calculer la moyenne et l'écart-type d'un créneau, les valeurs passées à plus de 5 écarts
médians de la médiane sont écartées : un incident passé gonflait l'écart-type et masquait les
suivants. Le message de l'alerte dit combien de relevés ont été écartés. Mesure sur le jeu
synthétique (`AnomalyDetectorEvaluationTests`) :

| Historique | Fausses alertes | Anomalies détectées |
|---|---|---|
| Propre, avant | 0,52 % | 100 % |
| Propre, après | 0,73 % | 100 % |
| 8 % d'heures polluées, avant | non mesuré | 31,1 % |
| 8 % d'heures polluées, après | 0,77 % | 99,4 % |

La même normale sert à la **latence des sondes** : un service nettement plus lent que d'habitude
ouvre une alerte d'anomalie rattachée au service, avant la panne. Un seul sens compte (un service
plus rapide n'est pas un problème), avec un plancher propre à la latence (5 ms ou 20 % de la
normale) pour qu'une série très régulière ne crie pas au loup pour trois millisecondes.

### 4. Score de santé

Un chiffre de 0 à 100 par équipement, calculé de façon déterministe et expliqué :

```
100 − alertes critiques (25 chacune, 60 au plus) − dégradé (15) − muet (20)
    − avertissements (8 chacun, 30 au plus) − part des indicateurs hors normale × 40
    − informations (2 chacune, 10 au plus)
```

Hors service donne 0 ; en maintenance, rien n'est jugé. Le score d'un site est la moyenne de ses
équipements jugés. Les plafonds par sévérité évitent qu'une pluie d'alertes d'information pèse
autant qu'une panne.

La part des indicateurs hors normale vient du détecteur d'anomalies, qui note son résultat par
équipement à chaque passage (`equipment_anomaly_rate`, V37) : le tableau de bord le lit sans rien
recalculer, et un taux plus vieux qu'une heure est ignoré.

## Alternatives écartées

- **Interfaces en modèles d'équipement** (une ligne `MetricDefinition` par port) : il faudrait
  recréer les définitions à chaque changement de port et le nombre d'indicateurs exploserait.
- **Lecture par GETBULK** : plus rapide qu'un GETNEXT, mais le découpage des réponses et les
  équipements qui le gèrent mal demandent un travail que la découverte horaire rend inutile.
- **Score de santé appris** (régression sur des incidents confirmés) : il n'y a pas encore
  d'historique d'incidents réels à l'EPT ; un score expliqué vaut mieux qu'un score opaque.
- **Mesurer la gigue sur une fenêtre glissante de plusieurs passages** : plus fidèle, mais il
  faudrait garder les temps de chaque paquet. L'écart moyen à l'intérieur d'un passage suffit à
  voir un lien qui se dégrade.

## Conséquences

- Les débits n'apparaissent qu'au deuxième passage, et après un redémarrage d'équipement.
- La découverte horaire veut dire qu'un port ajouté met jusqu'à une heure à apparaître.
- `net.usage` dépend de la vitesse annoncée par l'équipement (`ifHighSpeed`) : mal renseignée, elle
  fausse le pourcentage, qui reste plafonné à 100.
- Le ping à plusieurs paquets allonge chaque passage de sonde ICMP (environ une seconde).
- **Rien de tout cela n'a été vérifié sur du matériel réel** : les tests utilisent un faux
  équipement SNMP qui répond vraiment sur UDP, et un jeu de données synthétique. La validation sur
  l'infrastructure de l'EPT reste à faire.
