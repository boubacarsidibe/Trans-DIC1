# ADR 0055 : connecteur d'hyperviseur

- Statut : accepté
- Date : 2026-11-26
- Évolution demandée par le CRI (lot 3 : voir les machines virtuelles sans rien installer dedans)
- Complète l'[ADR 0006](0006-collection-realtime-runtime.md), l'[ADR 0014](0014-alert-correlation.md), l'[ADR 0019](0019-sondes-actives.md) et l'[ADR 0039](0039-regroupement-en-incidents.md) ; respecte l'[ADR 0018](0018-perimetre-par-site.md)

## Contexte

La plateforme voyait les machines physiques (SNMP, sondes) et les serveurs équipés d'un agent.
Les machines virtuelles, elles, n'existaient que si quelqu'un les avait créées à la main et y avait
installé un agent. Sur un cluster de virtualisation, les VM naissent, migrent et disparaissent sans
prévenir personne : l'inventaire décroche en quelques semaines, et quand un hôte tombe, rien ne dit
quelles VM tombent avec lui.

L'hyperviseur, lui, sait tout cela. Proxmox VE, VMware vSphere et Hyper-V tiennent la liste de
leurs VM, leur état, l'hôte où elles tournent et leur charge. Il suffit de le lui demander.

## Options

1. **Un agent dans chaque VM** (lot 1) : indispensable pour voir l'intérieur d'une VM, mais ne dit
   rien d'une VM éteinte, ni de l'hôte qui la porte, ni des VM où personne n'a installé d'agent.
2. **SNMP sur les hôtes** : les MIB des hyperviseurs sont pauvres, propres à chaque produit, et ne
   donnent pas l'hôte courant d'une VM qui migre.
3. **L'API de l'hyperviseur, en lecture seule, derrière un port applicatif** : une requête lit tout
   le cluster ; un connecteur par famille d'hyperviseur, le métier commun aux trois.

## Décision

Option 3, en commençant par Proxmox VE : son API REST est simple et son jeton d'API se limite
proprement à la lecture (rôle `PVEAuditor`). Les options 1 et 3 se complètent : un agent dans une VM
découverte par l'hyperviseur rejoint le même équipement (voir « adoption » plus bas).

### Un port, des adaptateurs remplaçables

`HypervisorSource` (module `collection`, paquet `port`) décrit ce que sait faire n'importe quel
connecteur : lire hôtes et invités, l'adresse d'un invité (agent invité), son UUID matériel. Le
métier — inventaire, topologie, mesures, alertes — vit dans `HypervisorCollectionService` et ne
connaît que ce port. `ProxmoxHypervisorSource` l'implémente ; `VsphereHypervisorSource` et
`HyperVHypervisorSource` sont des **squelettes documentés** (API à appeler, droits à demander) qui
se déclarent indisponibles : la plateforme refuse de configurer un connecteur indisponible. Les
écrire ne touchera rien d'autre.

### Secrets, certificat, garde anti-SSRF

- Le jeton ne vit pas en base : seulement sa référence `env:NOM`, résolue au moment de l'appel, comme
  les communautés SNMP. Ni l'API ni les journaux ni les messages d'erreur ne le contiennent ; un
  secret manquant est signalé par sa référence.
- Le certificat et le nom d'hôte sont **toujours vérifiés**. Une autorité interne peut être fournie
  (celle de Proxmox, `pve-root-ca.pem`) : seule elle est alors acceptée. Il n'existe pas d'option
  pour désactiver la vérification. Un certificat d'autorité est public : il est relu par l'API pour
  pouvoir être modifié ; une clé privée collée par erreur est refusée.
- L'adresse de l'API passe par la garde des sondes (ADR 0019), dans une **variante stricte** : toutes
  les adresses du nom doivent être autorisées, puisque le client HTTP choisit lui-même parmi elles.
  Boucle locale, lien local (dont l'adresse de métadonnées des clouds), multidiffusion : refusés, à
  la configuration et à chaque passage. HTTPS seulement, sans chemin ni identifiants dans l'adresse,
  redirections jamais suivies, réponse bornée à 32 Mo.

### Ce qu'un passage fait

Une tâche planifiée (sous verrou `JobGuard`, une instance à la fois) traite chaque connecteur dont
l'intervalle est échu. Chaque invité est traité dans sa propre transaction : une VM qui pose
problème n'annule pas le passage des autres. Les appels réseau se font hors transaction.

- **Un équipement par hôte (serveur) et par invité (machine virtuelle)**, retrouvé d'un passage à
  l'autre par l'identifiant de l'hyperviseur (`node/pve-01`, `qemu/101`, `lxc/201`). Un modèle de
  VM n'est jamais supervisé. Un conteneur LXC devient une « machine virtuelle » (le type
  d'équipement n'a pas de valeur « conteneur » ; le système le précise).
- **Numéro réutilisé** : l'UUID SMBIOS d'une VM est lu à sa découverte, puis relu quand son nom
  change. Même numéro, autre UUID : c'est une autre VM, elle a son propre équipement, l'ancienne est
  marquée disparue.
- **Adoption** : un équipement déjà inventorié à la même adresse dans le site, sans autre ressource
  d'hyperviseur, est repris plutôt que doublé (un serveur physique saisi à la main, une VM inscrite
  par son agent). Sans adresse connue, l'équipement reçoit une adresse provisoire unique (`hv-…`),
  remplacée par la vraie dès que l'agent invité la donne.
- **Lien VM → hôte** dans la table de topologie existante ; il est déplacé quand la VM migre (et
  c'est tracé). Seuls les liens vers les hôtes du connecteur sont touchés : un lien saisi à la main
  vers un commutateur reste. C'est ce lien qui permet au regroupement (ADR 0014, 0039) de ranger les
  alertes des VM sous celle de leur hôte.
- **Mesures** par le circuit d'ingestion commun, sous des clés propres (`hv.power`, `hv.cpu.usage`,
  `hv.memory.usage`, `hv.disk.usage`, `hv.net.in`, `hv.net.out`, `hv.uptime`) : elles ne se
  mélangent pas aux séries de l'agent quand une VM a les deux. Le débit réseau vient de deux
  lectures successives des compteurs.
- **Adresse et sonde** : l'adresse donnée par l'agent invité renseigne l'équipement, et une sonde
  ICMP est créée **inactive** (réglable par connecteur) : un administrateur décide de l'activer.
  Les lectures d'adresse sont bornées par passage (50 par défaut), une par VM nouvelle.

### Les alertes

- **VM éteinte** (ou suspendue) **après avoir été vue allumée** : alerte de collecte « VM … éteinte
  (hôte …) ». Une VM toujours restée éteinte (VM de secours) n'en ouvre pas. L'état éteint entre
  pourtant dans l'historique (`hv.power = 0`) : le circuit d'ingestion a reçu une entrée « observée »
  qui stocke et évalue sans **rétablir** l'équipement, sinon cette mesure fermerait aussitôt l'alerte
  qu'elle justifie. La VM rallumée referme l'alerte, comme n'importe quelle collecte qui reprend.
- **Hôte hors ligne selon le cluster** : alerte sur l'hôte, puis « VM … injoignable : son hôte … est
  hors ligne » sur ses VM, **rangées sous celle de l'hôte** (les hôtes sont traités avant les VM pour
  que l'alerte racine existe déjà). Un seul fait à traiter.
- **API injoignable** (réseau, délai) trois passages de suite : alerte sur les hôtes du connecteur.
  **Jeton ou certificat refusés, réponse illisible** : aucune alerte sur les hôtes — l'erreur ne dit
  rien de leur état — mais le connecteur affiche l'erreur et le nombre d'échecs.
- Un équipement qui a son propre agent actif est laissé à son chien de garde, comme pour les proxys
  (ADR 0047) : deux alertes pour une panne n'en font pas une plus visible.

### Une disparition n'est pas une suppression

Une VM ou un hôte absent de l'hyperviseur est marqué « supprimé de l'hyperviseur », tracé dans
l'audit, et affiché comme tel dans la liste des ressources. Son équipement et tout son historique
restent ; s'il réapparaît, il reprend sa place. Supprimer un connecteur ne supprime aucun
équipement.

## Conséquences

- Une VM créée sur le cluster apparaît dans l'inventaire au passage suivant (5 minutes par défaut),
  reliée à son hôte, sans geste de personne ; une VM qui migre suit son hôte dans la topologie.
- Un jeton d'API de l'hyperviseur, même en lecture seule, voit tout le cluster : c'est une donnée
  sensible, rangée dans l'environnement du serveur comme les autres secrets.
- Lire l'adresse d'une VM par son agent invité demande, selon la version de Proxmox, un droit de
  plus que `PVEAuditor` (`VM.Monitor`, ou `VM.GuestAgent.Audit` en Proxmox VE 9) ; sans lui, le
  connecteur fonctionne, les adresses restent inconnues et aucune sonde n'est créée.
- Les clés `hv.*` n'ont pas de règle d'alerte par défaut : les seuils se posent comme pour toute
  autre mesure.

## Limites

- **Jamais essayé contre un Proxmox réel** : aucun cluster n'était disponible. L'adaptateur est
  éprouvé par douze tests contre un faux serveur HTTPS qui rejoue des réponses enregistrées
  (`src/test/resources/proxmox`), dont le format a été écrit d'après la documentation de l'API
  Proxmox VE 8, avec un certificat généré au lancement : lecture des hôtes et invités, en-tête
  `PVEAPIToken`, adresses de l'agent invité, UUID SMBIOS, certificat refusé, jeton refusé (401, 403),
  délai dépassé, réponses invalides, erreur du serveur et port fermé. Le métier l'est par treize
  tests d'intégration contre PostgreSQL 16, l'API étant remplacée par un double : création sans
  doublon, liens et migration, adoption, alertes regroupées, VM éteinte puis rallumée, disparition,
  numéro réutilisé, pannes de l'API, administration.
- **La variante stricte de la garde laisse une fenêtre** entre la vérification du nom et la
  résolution faite par le client HTTP (rebinding DNS). Fermer cette fenêtre demanderait d'écrire le
  client HTTP à la main, comme pour les sondes ; pour une API d'hyperviseur configurée par un
  administrateur, le risque a été jugé acceptable.
- Une VM recréée sous le même numéro **et le même nom** entre deux passages garde l'équipement de la
  précédente (l'UUID n'est relu qu'au changement de nom).
- La charge disque d'une VM n'est connue qu'avec l'agent invité ; Proxmox renvoie 0 sans lui, que le
  connecteur ne transforme pas en faux zéro.
- VMware vSphere et Hyper-V : interface et squelettes seulement, faute de savoir lequel le CRI
  utilise (question posée).
