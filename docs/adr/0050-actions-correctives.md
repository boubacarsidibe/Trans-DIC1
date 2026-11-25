# ADR 0050 : actions correctives (remédiation automatique)

- Statut : accepté
- Date : 2026-11-25
- S'appuie sur l'agent (ADR 0006, 0012, 0023) et sur l'astreinte (ADR 0025)

## Contexte

Quand un service s'arrête, la plateforme sait l'ouvrir en alerte, prévenir et expliquer. Mais pour
les pannes les plus banales — un nginx tombé, un spouleur d'impression bloqué, un cache plein —
l'opérateur fait toujours le même geste : redémarrer. Zabbix sait lancer une commande à distance
quand un déclencheur s'ouvre. La question n'était pas de savoir si on pouvait le faire, mais **comment
le faire sans transformer la plateforme en outil d'exécution de commandes sur tout le parc**.

## Options

1. **La plateforme envoie une commande** (comme les « remote commands » de Zabbix). Simple, mais une
   plateforme compromise, ou un administrateur indélicat, peut alors exécuter n'importe quoi sur
   chaque serveur équipé d'un agent.
2. **La plateforme se connecte en SSH** et lance la commande. Il faut des clés sur chaque machine et un
   port entrant ouvert : tout ce que le choix de l'agent qui pousse (ADR 0006) voulait éviter.
3. **La plateforme ne transmet qu'un nom d'action ; l'agent associe ce nom à une commande dans sa
   propre configuration.**

## Décision

Option 3.

- La configuration de l'agent porte une section `[actions]` : `nom = commande`. Elle vit sur la
  machine supervisée et n'est modifiable que par son administrateur. Les actions ne viennent jamais
  de l'environnement : une variable héritée ne doit pas pouvoir ajouter une commande.
- L'agent **annonce** ses actions (noms et descriptions, jamais les commandes) à chaque passage, sur
  la même connexion HTTPS sortante que ses mesures : aucun port entrant.
- Une règle d'alerte peut porter une **politique** : nom de l'action, mode `AUTO` (dès l'ouverture)
  ou `APPROVAL` (un opérateur approuve), délai de grâce.
- L'exécution suit un cycle gardé : en attente d'approbation → en file → en cours → réussie ou en
  échec ; refusée ; expirée si l'agent ne la prend pas à temps (10 minutes), ou n'en rend pas compte.
- L'agent lance la commande **sans interpréteur** (tableau d'arguments), entrée fermée, durée bornée,
  sortie tronquée en gardant la fin (là où est l'erreur). Un nom inconnu est refusé (code 127).
- Garde-fous côté serveur : index unique (une même action jamais deux fois en même temps sur un
  équipement), délai de grâce par règle et équipement (une alerte qui clignote ne relance pas
  l'action en boucle), rien pendant une maintenance (l'événement d'ouverture n'est pas publié),
  chaque étape dans l'audit.
- Le déclenchement automatique écoute l'ouverture d'alerte dans la transaction de l'alerte, comme
  l'astreinte et la file des notifications.

## Conséquences

- Le retour à la normale ferme l'alerte tout seul : la boucle « détecter → corriger → constater »
  se fait sans humain pour les pannes connues, et le post-mortem (ADR 0053) le dit.
- L'agent n'acquiert aucun droit : une commande privilégiée passe par une règle sudo limitée à cette
  commande exacte (Linux) ou par un droit accordé au compte `LOCAL SERVICE` (Windows).
- Délai : l'action part au prochain passage de l'agent (au plus un intervalle de collecte, 60 s par
  défaut).
- Non fait : enchaînement de plusieurs actions, conditions sur le résultat, actions sur les
  équipements réseau (pas d'agent : il faudrait SSH ou SNMP en écriture, refusés ici).
- Jamais essayé sur un serveur de l'EPT.
