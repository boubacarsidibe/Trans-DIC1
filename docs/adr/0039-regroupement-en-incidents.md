# ADR 0039 : regroupement des alertes en incidents

- Statut : accepté
- Date : 2026-11-23
- Lot 2, M10 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0012](0012-alert-correlation.md)

## Contexte

Une panne en amont fait tomber cinq machines. Jusqu'ici la plateforme ouvrait cinq alertes et
réveillait cinq fois. Le regroupement de l'ADR 0012 savait éviter cela, mais seulement pour les
**pertes de collecte**, et seulement en suivant la topologie : les seuils, les anomalies, les
événements SNMP, les prévisions et les alertes de sécurité restaient chacun de leur côté.

Un serveur dont le processeur, la mémoire et la latence partent ensemble produisait trois alertes.
Une inondation qui touche tout un site en produisait une par machine. Chacune est vraie ; aucune ne
dit que c'est **un seul fait**, et c'est précisément ce que l'opérateur a besoin de savoir en
premier.

## Décision

Introduire l'**incident** : un objet de première classe qui rassemble les alertes d'un même fait,
tous types confondus, avec **une seule notification**.

### Les règles, dans cet ordre

Toutes exigent que l'alerte arrive dans la fenêtre de l'incident (dix minutes par défaut, comptée
depuis sa dernière alerte, et non depuis son ouverture : un fait qui s'étend prolonge sa fenêtre).

1. **Même équipement.** Trois alertes sur le même serveur en dix minutes, c'est un fait, pas trois.
2. **Topologie.** L'équipement est à deux liens ou moins d'un équipement de l'incident. Le parcours
   est **non orienté** : un amont en panne explique son aval, mais un aval en panne peut tout aussi
   bien révéler le lien lui-même, et la topologie du campus n'est pas un arbre parfait. C'est le
   signal le plus fort après le précédent, parce qu'il dit *par quoi* deux alertes peuvent être
   liées.
3. **Même site et même nature.** Même site, et soit la même règle d'alerte, soit des messages de
   gabarit quasi identique. Sans la condition de nature, tout ce qui arrive en même temps sur un
   site finirait dans le même sac.

Chaque rattachement **porte sa raison en clair** — « Voisin dans la topologie : Routeur de cœur
(lien direct) » —, et l'écran la montre. Un regroupement que l'opérateur ne peut pas vérifier, il
ne lui fera pas confiance, et il aura raison : une fenêtre de temps rapproche aussi des faits qui
n'ont rien à voir.

### La ressemblance des messages : ce qu'elle vaut, mesuré

Le plan proposait la « ressemblance du message » comme troisième critère. Nous l'avons implémentée
(indice de Jaccard sur les mots, chiffres et noms propres retirés) **puis mesurée sur les messages
que la plateforme écrit vraiment** — `MessageSimilarityEvaluationTests`. Le résultat est négatif, et
mérite d'être dit :

| Couple | Part de mots communs |
| --- | --- |
| « Processeur à 92 % sur A » / « Processeur à 87 % sur B » | 1,0 |
| « Débit entrant saturé » / « Débit entrant inhabituel » (même fait) | 0,5 |
| « Débit entrant saturé » / « Débit sortant saturé » (faits différents) | **0,5** |
| « Interface coupée » / « Plus de données reçues » (même fait) | 0,0 |

Les deux populations se chevauchent exactement : aucun seuil ne sépare « même fait » de « faits
différents ». Le seuil est donc fixé à **0,6**, au-dessus du chevauchement, là où ne passent plus que
des messages de gabarit quasi identique. La règle ne sert alors qu'à un cas — *la même alerte sur
plusieurs machines d'un site* — mais c'est un cas fréquent, et le schéma ne porte pas la clé
d'indicateur sur l'alerte, donc il n'y a pas de signal plus direct pour les détecteurs sans règle.
Descendre à 0,5 attraperait deux couples utiles de plus et un faux avec : un faux regroupement coûte
plus cher qu'une notification de trop, parce qu'il fait croire que deux faits n'en sont qu'un et
détourne d'une piste.

Les couples « même fait, autres mots » sont laissés à la topologie, qui dit par quoi deux alertes
sont liées au lieu de le deviner.

### Une notification, et une seule — sauf si cela s'aggrave

L'incident retient la plus haute sévérité **déjà notifiée**. Une alerte prend la parole si rien ne
l'a encore été, ou si elle est plus grave que ce qui l'a été : un incident notifié en avertissement
qui devient critique doit réveiller quelqu'un une seconde fois. Un simple booléen ne l'aurait pas
permis.

La décision est prise **une fois, au rattachement**, et conservée sur la ligne de rattachement. Il y
a deux chemins de notification pour une même alerte — la file des notifications et la chaîne
d'astreinte — et une décision consommée par le premier aurait fait taire le second.

Les modules `reporting` et `oncall` n'ont pas le droit de dépendre des services d'`alerting` (voir
`archunit.md`), et c'est pourtant là que la décision doit se prendre. Elle leur parvient par un
**port** `common.alerting.IncidentGrouping`, même idiome que `common.scope.SiteScope`. Une alerte
qu'aucun incident n'a rangée notifie toujours : le regroupement ne doit jamais faire taire ce qu'il
n'a pas compris.

### Deux écouteurs, et pourquoi

La notification part de `AlertChangedEvent` ; le rattachement doit donc être fait **avant** que la
file ne traite cet événement, d'où l'ordre le plus prioritaire sur cet écouteur. Mais une alerte
sous maintenance, ou déjà regroupée par l'ADR 0012, ne publie jamais cet événement : elle
n'arriverait dans aucun incident et manquerait à l'écran. `AlertActivityEvent`, lui, est publié dans
tous les cas et sert de filet — **sans jamais donner la parole**, puisque personne n'est prévenu
pour cette alerte-là. Sans cette distinction, une alerte muette emporterait la voix de l'incident et
la suivante, qui devait notifier, serait restée silencieuse.

### Pas de second cycle de vie

Un incident est **OPEN** ou **RESOLVED**, rien de plus, et il se referme seul quand toutes ses
alertes sont retombées. Il n'y a ni prise en charge ni assignation d'incident : ce sont les alertes
qui portent le travail, et deux cycles de vie concurrents créeraient deux vérités sur l'état d'une
panne.

## Alternatives écartées

- **Étendre le lien parent/enfant de l'ADR 0012 à tous les types** : il aurait fallu greffer le
  regroupement dans les neuf services qui ouvrent des alertes, et un groupe sans identité propre
  n'aurait donné ni écran, ni durée, ni « une notification par fait ».
- **Un score pondéré** (temps × topologie × ressemblance) au lieu de règles ordonnées : il faut
  alors inventer trois poids que rien ne justifie — exactement ce que M12 reproche au diagnostic —
  et le résultat n'est plus explicable à l'opérateur en une phrase.
- **Grouper sur la seule fenêtre de temps**, comme certains outils : sur un campus, tout arrive en
  même temps à l'heure de pointe.
- **Faire porter le regroupement par une tâche planifiée** plutôt que par l'ouverture de l'alerte :
  la notification serait déjà partie.
- **Donner un site « nul » à un incident qui franchit un lien inter-sites** : il deviendrait
  invisible à tous les comptes limités à des sites. Il garde donc le site où il a commencé.

## Conséquences

- **Une notification de moins par alerte groupée.** Le compteur `alerting.incident.grouped` mesure
  le bruit évité, et `alerting.incident.silenced` les notifications réellement supprimées.
- **Il y a maintenant deux interrupteurs de regroupement, et c'est un piège.** `ALERT_CORRELATION_ENABLED=false` coupe le lien parent/enfant de l'ADR 0012 sur les pertes de collecte ; il ne rend plus une notification par alerte, parce que les incidents, eux, regroupent toujours. Pour revenir au comportement d'avant il faut aussi `ALERT_INCIDENT_ENABLED=false`. Un test le dit explicitement (`AlertCorrelationTests.leRegroupementPeutEtreDesactive`), parce que personne ne devrait le découvrir en exploitation.
- **Un incident qui franchit un site reste rattaché à son site d'origine.** Un opérateur limité à
  l'autre site ne le voit pas dans la liste ; il voit malgré tout ses propres alertes sur l'écran
  des alertes, et le détail d'un incident ne montre que les alertes des sites qu'il a le droit de
  voir.
- **Le regroupement peut se tromper**, surtout par la règle du même site. C'est pourquoi chaque
  rattachement est expliqué et visible : l'opérateur garde le dernier mot en lisant les alertes une
  par une, qui n'ont pas bougé.
- **Sur une petite topologie, deux liens suffisent à relier tout le monde.** Vérifié sur l'instance de démonstration : cinq équipements en étoile autour d'un routeur, deux règles franchies en même temps, et les onze alertes se retrouvent dans un seul incident avec une seule notification. C'est juste ici — les cinq machines sont bien reliées — mais cela montre que le site d'école collapse facilement en un incident unique. Sur un parc plus maillé, `ALERT_INCIDENT_MAX_DISTANCE=1` (voisins directs seulement) sera souvent le bon réglage, et c'est pourquoi c'en est un.
- **Un fait qui s'étend lentement**, plus de dix minutes entre deux symptômes, fera deux incidents.
  Élargir la fenêtre réduirait ce découpage mais rapprocherait des faits étrangers ; dix minutes est
  un réglage, pas une vérité.
- **Le titre est le message de la première alerte vue**, qui n'est pas toujours la cause : les
  alertes de collecte adoptées par l'ADR 0012 peuvent arriver avant elle.
- L'ordre des écouteurs Spring devient **structurel** : déplacer l'écouteur du regroupement, ou
  publier `AlertChangedEvent` avant d'avoir sauvegardé l'alerte, remettrait les notifications
  d'aplomb sans que rien ne le signale. `IncidentNotificationTests` existe pour cela.
- Le coût est d'une requête de voisinage et d'un parcours des incidents ouverts de la fenêtre par
  alerte ouverte : négligeable pour un parc d'école.
- **Rien n'a été éprouvé sur des pannes réelles de l'EPT.** Les cas d'épreuve sont construits à la
  main. La part d'alertes correctement regroupées sur le parc réel reste à mesurer, et c'est ce
  chiffre — pas le nôtre — qui dira si les trois règles sont les bonnes.
