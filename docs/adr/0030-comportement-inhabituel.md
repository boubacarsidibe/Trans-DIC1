# ADR 0030 : comportement inhabituel d'un compte ou d'un serveur

- Statut : accepté
- Date : 2026-11-11
- Complète l'[ADR 0028](0028-supervision-securite.md) (S5 de `docs/analyse-ecarts-concurrents-et-ml.md`)

## Contexte

Les détections de l'ADR 0028 comptent sur une fenêtre courte : dix échecs d'authentification en
cinq minutes, quarante requêtes en erreur en cinq minutes. Deux attaques passent entre les mailles :

- **l'attaque patiente** : trois mots de passe par minute pendant la nuit ne franchissent jamais le
  seuil, et font pourtant mille essais ;
- **le compte volé** : celui qui connaît le mot de passe ne produit aucun échec. Du point de vue
  des journaux, c'est une connexion réussie comme une autre.

Les signatures (ADR 0028) et le modèle appris ([ADR 0029](0029-modele-appris-requetes-web.md))
jugent le *contenu* d'une requête. Ni l'un ni l'autre ne regarde ce qui est habituel pour un compte
ou pour un serveur.

## Décision

Deux détections fondées sur l'habitude, et seulement sur ce que la plateforme conserve déjà.

### Connexion depuis une adresse jamais vue

À l'arrivée d'un lot de journaux, avant tout enregistrement — la question ne vaut que sur l'état
d'avant le lot —, chaque connexion réussie est comparée à l'historique du compte sur cet
équipement, dans la limite de ce qui est conservé (30 jours) :

- le compte doit avoir des habitudes : au moins `threat.known-logins` (5) connexions réussies ;
- aucune de ces connexions ne doit venir de l'adresse du jour.

Alors un **avertissement** s'ouvre, un seul par compte et par équipement, qui nomme l'adresse, le
nombre de connexions connues et le nombre d'adresses habituelles.

Le seuil des cinq connexions est ce qui évite d'alarmer sur tout le parc le lendemain de
l'installation : un compte dont on ne sait rien n'a pas d'habitude à trahir.

### Volume inhabituel dans l'heure

Chaque heure, pour chaque équipement, le nombre d'échecs d'authentification et de requêtes en
erreur de l'heure écoulée est comparé au **même créneau les jours précédents** — 14 h se compare à
14 h, parce qu'un service n'a pas la même vie le matin et la nuit. La normale est calculée par la
statistique robuste déjà employée pour les indicateurs ([ADR 0015](0015-anomaly-detection.md)) :
médiane et écart médian, les jours eux-mêmes anormaux écartés, pour qu'une attaque passée ne
relève pas la barre des suivantes.

Les heures sans aucun événement comptent pour zéro — sans cela, un serveur d'ordinaire silencieux
n'aurait pour historique que ses mauvais jours — mais seulement depuis le premier journal reçu de
cet équipement : inventer des zéros d'avant l'installation de l'agent ferait passer la première
journée normale pour une attaque.

**Le plancher d'écart-type est celui d'un comptage, pas celui d'un taux.** Le détecteur d'origine
plancher l'écart-type à 1 % de la moyenne, ce qui convient à une occupation disque. Pour un nombre
d'événements, un créneau qui vaut 30 tous les jours aurait un écart-type de 0,3, et 32 passerait
pour une anomalie à six écarts-types. Le bruit naturel d'un comptage croît comme sa racine (loi de
Poisson) : c'est ce plancher-là qui est retenu quand l'historique est plus régulier que le hasard
ne le permet. S'y ajoute un plancher absolu (`threat.volume-floor`, 20 événements) : six échecs sur
un serveur d'ordinaire muet ne valent pas qu'on réveille quelqu'un.

Seul l'excès est signalé. Une heure plus calme que d'habitude peut vouloir dire qu'un service est
tombé, mais c'est le travail de la supervision, pas celui de la sécurité.

## Alternatives écartées

- **Une normale par adresse source** : une adresse d'attaquant est nouvelle par définition, elle
  n'a pas d'habitude. C'est le compte et le serveur qui en ont une.
- **Compter toutes les requêtes, pas seulement les erreurs** : il faudrait conserver une trace des
  requêtes ordinaires, ce que l'ADR 0028 refuse. Un comptage agrégé par heure, sans URL ni adresse,
  resterait possible ; il n'est pas fait.
- **Une alerte critique sur une connexion depuis une adresse nouvelle** : un enseignant en
  déplacement ou un changement d'opérateur suffit à la déclencher. Avertissement, donc, et un
  message qui dit d'appeler la personne avant d'en conclure quoi que ce soit.
- **Résolution automatique au retour à la normale** : les alertes de sécurité de l'ADR 0028 ne se
  résolvent pas seules non plus. Une attaque qui s'arrête n'est pas une attaque qui n'a pas eu
  lieu ; c'est à quelqu'un de clore après avoir regardé.

## Conséquences

- **Ces deux détections signalent un changement d'habitude, pas une attaque prouvée.** Le jour de
  la rentrée ne ressemble à aucun autre, et une personne en mission se connecte d'ailleurs. D'où
  l'avertissement et non le critique, et des messages qui disent quoi vérifier.
- Il faut de l'histoire pour juger : au moins trois jours comparables pour le volume, cinq
  connexions pour un compte. Les premiers jours après l'installation, rien ne se déclenche. C'est
  voulu.
- La fenêtre de comparaison ne peut pas dépasser la durée de conservation (30 jours) : ce qui est
  purgé n'est plus comparable.
- Un attaquant qui connaît ces règles peut rester dessous : mille essais répartis sur vingt-quatre
  heures depuis vingt adresses ne déclenchent rien. Aucune de ces méthodes ne prétend au contraire.
- **Rien n'a été éprouvé sur le trafic réel de l'EPT.** Les seuils (5 connexions, 4 écarts-types,
  20 événements) sont des choix raisonnés, pas des valeurs mesurées sur le parc de l'école. Ils se
  règlent sans redéployer (`threat.*`), et les deux détections se coupent séparément.
