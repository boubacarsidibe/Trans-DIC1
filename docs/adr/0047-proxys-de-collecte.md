# ADR 0047 : proxys de collecte par site, avec tampon hors ligne

- Statut : accepté
- Date : 2026-11-23
- Lot 4 (section 2 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0012](0012-agent.md) et l'[ADR 0039](0039-regroupement-en-incidents.md)

## Contexte

Chaque équipement portait son propre agent et son propre jeton. Un site derrière une liaison qui
tombe perdait donc ses mesures, sans rattrapage : rien dans le serveur ne savait recevoir un lot
vieux de six heures, et l'horodatage passé n'était pas prévu pour autre chose qu'une dérive
d'horloge. La comparaison aux outils du marché notait l'absence de proxys de collecte, que Zabbix,
LibreNMS et Checkmk offrent tous.

Pour un campus comme celui de l'École Polytechnique de Thiès, c'est le cas courant et non
l'exception : des bâtiments reliés par des liaisons qu'on ne maîtrise pas, et une coupure de
quarante minutes qui laissait un trou définitif dans l'historique — donc dans les rapports de
disponibilité, et dans ce qu'apprennent les modèles.

## Décision

Un **proxy par site** : un seul jeton pour tous les équipements du site, un envoi groupé, et un
tampon côté proxy que le serveur sait recevoir après coup.

### Un jeton de site, et rien de plus

Le jeton d'un proxy vaut pour son site et pour aucun autre. Une mesure visant un équipement d'un
autre site est écartée et comptée : volé dans un local technique, un jeton de proxy ne doit pas
permettre d'écrire sur tout le campus. La chaîne de sécurité est distincte de celle des agents, et
pas un rôle de plus dans celle-ci — un jeton d'agent n'ouvre pas l'envoi groupé d'un site, un jeton
de proxy n'ouvre pas l'envoi d'un équipement qu'il ne relaie pas.

### Ce qui compte le plus : un tampon rejoué ne réveille personne

Rejouer six heures de mesures ouvrirait et refermerait six heures d'alertes **comme si elles
arrivaient maintenant**. L'astreinte serait réveillée à trois heures du matin pour une panne
terminée depuis longtemps, et le jour où cela se produirait vraiment, plus personne ne regarderait
ces alertes.

Donc : une mesure antérieure à la fenêtre de fraîcheur (dix minutes par défaut) est **stockée sans
être évaluée**. Elle remplit l'historique, les rapports de disponibilité et ce qu'apprennent les
modèles ; elle n'entre pas dans la liste des alertes. Les mesures récentes du même lot, elles, sont
évaluées normalement : l'état d'aujourd'hui est dans la dernière mesure du tampon, et une panne
encore en cours doit ressortir.

Ce n'est pas la même chose que marquer la mesure « de mauvaise qualité », et le code ne le fait
pas : une mesure ancienne est exacte, elle est seulement hors sujet pour les alertes. Le drapeau est
explicite dans la signature de stockage, pour que la distinction reste lisible.

### Un lot n'échoue jamais en entier pour une mesure fautive

Un proxy qui reçoit 400 renvoie le même lot. Si une seule mesure trop vieille ou mal adressée
faisait échouer tout l'envoi, le tampon du site ne se viderait **plus jamais** : la collecte serait
gelée par une ligne de configuration. Les mesures fautives sont donc comptées dans le bilan
(`tropVieilles`, `horsSite`) et le proxy sait quoi oublier. Seul un lot vide ou démesuré est refusé,
parce que c'est une erreur du proxy et non un tampon qui se vide.

### Un rejeu renvoie le premier bilan

Le proxy numérote son lot. Un accusé de réception perdu — le cas normal quand la liaison est
mauvaise — ferait sinon stocker deux fois les mêmes mesures, et les moyennes horaires seraient
fausses. Le serveur se souvient des lots acceptés pendant deux jours et renvoie le bilan du premier
passage. Deux jours suffisent : la trace ne sert qu'à reconnaître un rejeu, pas à archiver.

### Le silence d'un proxy est le silence d'un site

Un proxy muet veut dire qu'un site entier est sans collecte. Le chien de garde ouvre alors une
alerte de collecte **par équipement relayé**, et non une seule pour le site : une alerte est
attachée à un équipement, et inventer un équipement « site » pour y accrocher la panne aurait menti
sur l'inventaire. Le regroupement en incidents (ADR 0039) fait le reste — mêmes site et message dans
la fenêtre, **un seul incident à traiter**. Ce sont deux mécanismes qui existaient déjà, et ils
suffisent ; un test le vérifie sur deux équipements.

Un équipement qui a son propre agent actif est laissé à son chien de garde : deux alertes pour une
panne n'en font pas une plus visible.

L'alerte se résout à la **première mesure reçue, y compris rejouée du tampon**. C'est voulu, et
c'est la seule entorse à la règle ci-dessus : une mesure vieille de six heures ne prouve rien du
présent, mais le fait de la recevoir prouve que la liaison est revenue — et c'est exactement ce que
dit une alerte de collecte. Une alerte de seuil, elle, ne se résout pas ainsi, puisqu'elle dépend de
l'évaluation.

## Alternatives écartées

- **Un index unique sur (équipement, indicateur, horodatage)** pour écarter les doublons au lieu du
  numéro de lot : la base refuserait les rejeus, mais le proxy recevrait une erreur et ne saurait
  pas que son envoi avait déjà abouti. Et la contrainte s'appliquerait à toutes les sources, pour un
  besoin qui n'est le leur.
- **Accepter les mesures anciennes comme les autres** et compter sur l'opérateur pour ignorer les
  alertes rétroactives : c'est demander à quelqu'un de trier à trois heures du matin ce que le
  serveur sait trier.
- **Un proxy qui s'authentifie par mTLS** : plus solide, mais il faudrait distribuer et renouveler
  des certificats sur des machines de bâtiment, là où le jeton suit le chemin déjà éprouvé des
  agents. À reprendre si le CRI met en place une autorité de certification.
- **Faire du proxy un agent comme les autres, attaché à un équipement fictif** : l'inventaire se
  serait rempli d'équipements qui n'existent pas, et toutes les vues du campus avec.
- **Un tampon côté serveur** (accepter puis traiter plus tard) : ne résout rien, le problème est que
  la liaison est coupée avant le serveur.

## Conséquences

- **Sans proxy enregistré, rien ne change.** Les agents et la collecte SNMP fonctionnent comme
  avant ; un site peut mélanger les deux, et le chien de garde ne compte pas deux fois.
- **Un trou dans l'historique devient un retard, pas une perte.** Les rapports de disponibilité
  d'une journée se stabilisent donc après le rattrapage, et c'est à dire à qui les lit.
- **Le logiciel du proxy n'est pas livré ici.** Ce dépôt expose le contrat (`POST
  /api/v1/proxy/measures`, numéro de lot, fenêtres) et l'éprouve de bout en bout ; le relais à
  installer dans un bâtiment reste à écrire, et il n'a besoin que de ce contrat.
- **L'enregistrement d'un proxy n'a pas été exercé sur l'instance de démonstration**, faute d'accès
  au mot de passe de l'administrateur de démonstration depuis cette session. Ce qui a été vérifié en
  conditions réelles : la migration s'applique, la route refuse tout jeton qui n'est pas celui d'un
  proxy actif, et le passage planifié tourne sans erreur. Le reste — rejeu, fenêtres, hors-site,
  regroupement en un incident — est éprouvé par dix-sept tests contre la base réelle.
- **Deux réglages à comprendre avant de les changer** :
  `collection.proxy.freshness-seconds` (au-delà, on ne réveille personne) et
  `collection.proxy.backfill-hours` (au-delà, on oublie). Allonger la fraîcheur, c'est accepter des
  alertes rétroactives ; la raccourcir, c'est risquer de ne pas voir une panne en cours au retour de
  la liaison.
