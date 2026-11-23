# ADR 0046 : connexion par l'annuaire LDAP

- Statut : accepté
- Date : 2026-11-23
- Lot 4 (section 2 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0019](0019-connexion-unique-oidc.md)

## Contexte

La plateforme sait authentifier de deux façons : un compte local avec mot de passe, et la connexion
unique OIDC si le CRI fournit un fournisseur d'identité. La comparaison aux outils du marché notait
l'absence de LDAP et d'Active Directory, que Zabbix, LibreNMS et Checkmk offrent tous — et c'est ce
que beaucoup d'écoles ont réellement, souvent sans fournisseur OIDC.

Sans cela, le CRI doit créer et tenir à jour un compte local par personne, avec un second mot de
passe à retenir et à faire tourner.

## Décision

Déléguer l'authentification à l'annuaire quand il est configuré, en **liaison simple** : la
plateforme présente le nom distingué de la personne et son mot de passe, l'annuaire dit oui ou non.

### Aucune bibliothèque ajoutée

JNDI est dans le JDK depuis toujours et suffit à une liaison et à une recherche. Une bibliothèque
LDAP apporterait des pools de connexions et une gestion fine des référents dont nous n'avons pas
l'usage, pour un arbre de dépendances de plus à surveiller — et le projet a déjà dix vulnérabilités
signalées sur ses dépendances actuelles.

### Un compte est local ou délégué, jamais les deux

Un compte provisionné depuis l'annuaire porte un drapeau et **un mot de passe local inutilisable**
(une valeur aléatoire que personne ne peut présenter). Garder un second secret aurait annulé
l'intérêt de déléguer : un mot de passe local périmé continuerait d'ouvrir la porte après un
changement côté annuaire.

Un compte local **n'est jamais transformé** en compte d'annuaire à son insu. Si l'annuaire connaît
un nom qu'un compte local porte déjà, la connexion par l'annuaire est refusée et le journal le dit :
c'est à un administrateur de trancher, pas à une heuristique.

Un compte local s'authentifie localement, et son mot de passe **ne part pas sur le réseau** — un
test le vérifie, parce que l'inverse serait une fuite silencieuse.

### Aucun groupe reconnu veut dire aucun accès

Le rôle vient des groupes de l'annuaire, par une correspondance configurée
(`ldap.group-admin`, `ldap.group-operator`, `ldap.group-viewer`). **Il n'y a pas de rôle par
défaut** : donner « lecteur » à toute personne figurant dans l'annuaire ouvrirait la supervision du
campus à tous les étudiants. C'est le genre de défaut qu'on ne découvre qu'après.

Quand aucun groupe n'est reconnu, le mot de passe était bon mais la connexion est refusée, et
l'événement est journalisé **avec les groupes vus**. Sans eux, un administrateur ne saurait pas quel
nom écrire dans la configuration et chercherait une panne d'annuaire là où il n'y a qu'une ligne à
remplir.

Le rôle le plus fort gagne : quelqu'un à la fois dans le groupe des administrateurs et dans celui
des lecteurs est administrateur. L'inverse serait une surprise désagréable le jour où on l'ajoute à
un groupe large. La comparaison ignore la casse, les espaces, et accepte un nom distingué complet
comme un nom court — parce qu'un nom de groupe se recopie à la main depuis une console
d'administration, et qu'un « NOC » contre « noc » bloquerait l'accès sans que rien ne le dise.

### Le mot de passe n'est pas envoyé en clair

Une liaison simple sur `ldap://` envoie le mot de passe tel quel sur le réseau. **Le service refuse
de le faire**, se déclare inactif, et écrit dans le journal comment le corriger. L'exploitation peut
passer outre par `ldap.allow-insecure=true`, mais il faut l'écrire : c'est le genre de réglage qu'on
ne veut pas voir arriver en production par inadvertance.

Les référents ne sont jamais suivis (`REFERRAL=ignore`) : un annuaire pourrait renvoyer vers un
serveur que nous n'avons pas choisi, et nous lui enverrions le mot de passe.

### Les injections sont fermées des deux côtés

Le nom d'utilisateur est échappé selon la RFC 4514 avant d'entrer dans le nom distingué, et le nom
distingué selon la RFC 4515 avant d'entrer dans le filtre de recherche de groupes. Sans cela, un nom
d'utilisateur contenant `,ou=admins` construirait un autre nom distingué que celui voulu.

### Les groupes se lisent de deux façons

`memberOf` sur l'entrée de la personne s'il existe (Active Directory le fournit), sinon une
recherche dans `ldap.group-search-base` avec un filtre configurable (OpenLDAP sans l'extension
`memberof` l'exige). Prendre en charge une seule des deux aurait exclu la moitié des annuaires.

## Alternatives écartées

- **Un compte de service qui cherche la personne puis vérifie son mot de passe** : il faudrait
  stocker le mot de passe de ce compte, qui a souvent le droit de lire tout l'annuaire. Le gabarit
  de nom distingué évite ce secret, au prix d'un arbre dont la forme doit être régulière — ce qui
  est le cas presque partout.
- **SAML** : demande un échange de métadonnées, une signature XML et une bibliothèque ; l'écart au
  marché notait LDAP d'abord, et le CRI aura plus vraisemblablement un annuaire qu'un fournisseur
  SAML.
- **Un serveur LDAP embarqué pour les tests** (UnboundID) : une dépendance de test de plus, non
  présente dans le dépôt local, et le projet se compile hors ligne. Le port `Directory` permet
  d'éprouver tout le raisonnement sans serveur ; le dialogue réseau reste non éprouvé, et c'est dit
  ci-dessous.
- **Synchroniser périodiquement l'annuaire** (créer tous les comptes d'avance) : il faudrait un
  compte de lecture et un travail de fond, pour un gain nul — un compte sert le jour où la personne
  se connecte.

## Conséquences

- **Sans `ldap.url` ni `ldap.user-dn-pattern`, rien ne change.** La fonction est invisible jusqu'à
  ce que l'exploitation la configure, ce qui est le bon défaut.
- **Le dialogue réseau avec un annuaire n'a jamais été exécuté.** Il n'y a pas d'annuaire sur le
  poste de développement, et le projet n'ajoute pas de serveur de test. Tout ce qui se raisonne est
  éprouvé — gabarit, échappement, correspondance des groupes, refus du clair, provisionnement,
  verrouillage — mais **la liaison elle-même reste à essayer contre l'annuaire du CRI**, et c'est la
  limite la plus importante de cette décision. Le code derrière le port est volontairement mince
  pour cette raison.
- **Une session en cours n'est pas coupée** quand les droits changent dans l'annuaire : le rôle est
  relu à la connexion suivante. Un retrait urgent passe par la désactivation du compte local, qui
  prime sur l'annuaire.
- **Le verrouillage après échecs répétés s'applique aussi aux comptes d'annuaire.** Sans lui, la
  plateforme servirait de bélier pour essayer des mots de passe sur les comptes de l'école.
- **L'adresse électronique n'est reprise qu'à la création.** Elle est unique en base, et la corriger
  à chaque connexion ferait échouer la connexion le jour où deux comptes se la disputent. La
  modifier ensuite appartient à l'administration des comptes.
- **Pas de StartTLS**, seulement `ldaps://`. Un annuaire qui n'écoute qu'en 389 avec StartTLS devra
  exposer 636, ou l'exploitation devra assumer `allow-insecure` sur un réseau qu'elle maîtrise.
