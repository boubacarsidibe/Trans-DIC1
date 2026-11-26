# ADR 0057 : correction à distance enrichie

- Statut : accepté
- Date : 2026-11-26
- Évolution demandée par le CRI (lot 5 : corriger à distance sans perdre la main)
- Complète l'[ADR 0050](0050-actions-correctives.md) sans en changer le principe ; s'appuie sur
  l'[ADR 0025](0025-astreinte.md) (astreinte) et l'[ADR 0055](0055-connecteur-hyperviseur.md)
  (hyperviseur) ; respecte l'[ADR 0018](0018-perimetre-par-site.md)

## Contexte

L'ADR 0050 a posé la règle : la plateforme ne transmet qu'un **nom** d'action, la commande est écrite
sur la machine. Il restait quatre manques, relevés par le CRI :

1. **Rien ne disait si l'action avait servi.** Une action « réussie » (code 0) peut très bien laisser
   le service en panne ; l'alerte restait ouverte et personne n'était prévenu de l'échec de la
   correction.
2. **Une panne qui revient sans cesse était corrigée sans cesse.** Le délai de grâce empêche la boucle
   serrée, pas le redémarrage de nginx dix fois par jour, qui cache une vraie panne.
3. **Écrire les actions sur cent machines** à la main, avec la bonne règle sudo, n'était pas outillé ;
   pire, le durcissement systemd de l'agent (`NoNewPrivileges`, `ProtectSystem=strict`, ensemble de
   capacités vide) empêchait `sudo` de fonctionner : les actions de l'ADR 0050 ne pouvaient pas
   aboutir sous systemd.
4. **Une VM figée** ne répond plus à son agent : seul l'hyperviseur peut la relancer.

## Options

Pour la vérification : (a) demander à l'agent de rejouer un contrôle après l'action — il faudrait un
second nom d'action par action, et l'agent jugerait lui-même son travail ; (b) **juger sur l'alerte**
— l'alerte est la mesure indépendante que la plateforme possède déjà.

Pour la VM : (a) ouvrir l'API de l'hyperviseur en écriture au jeton de collecte — un jeton qui lit
tout et peut tout faire ; (b) **un second jeton, distinct, au seul droit d'agir sur l'alimentation**,
avec approbation systématique.

## Décision

Options (b) et (b).

### Vérification après l'action

- Chaque politique de règle reçoit un **délai de vérification** (`verify_minutes`, 0 à 240, 0 par
  défaut = comportement de l'ADR 0050). Quand l'agent rend compte d'une action réussie, l'échéance est
  fixée ; une tâche planifiée juge ensuite : alerte résolue (ou archivée) → **efficace** ; alerte
  toujours ouverte (ouverte ou prise en charge) → **inefficace**. Une action en échec est inefficace
  tout de suite.
- Une correction inefficace **n'est jamais relancée automatiquement** : tant qu'une exécution de la
  même action sur le même équipement a été jugée inefficace dans la fenêtre de la politique, la
  prochaine ouverture passe d'office en approbation.
- Elle **prévient l'astreinte tout de suite** (ADR 0025) : la remédiation publie l'événement
  `common.alerting.RemediationNeedsHuman` ; l'astreinte, qui l'écoute, envoie un rappel à la personne
  principale (au secondaire si le principal n'est pas joignable), hors de la chaîne d'escalade, avec la
  raison dans la chronologie de l'alerte. Les deux modules ne dépendent pas l'un de l'autre.
  Conditions identiques à l'ouverture : alerte encore ouverte, sévérité au moins celle de l'astreinte,
  hors maintenance, site couvert par un planning. Un même fait ne prévient qu'une fois (clé de
  déduplication).

### Limite de fréquence

- Chaque politique porte `max_auto_runs` (1 à 100, 3 par défaut) sur `window_hours` (1 à 168, 24 par
  défaut). Au-delà, pour cet équipement et cette action, la politique automatique passe **d'office en
  approbation** : l'exécution porte la raison (« limite de fréquence atteinte (3 exécutions
  automatiques en 24 h) »), c'est audité (`REMEDIATION_FORCED_APPROVAL`) et l'astreinte est prévenue
  par le même événement. Seules comptent les exécutions automatiques remises à l'agent (en file, en
  cours, réussies ou en échec) ; une demande refusée ou expirée ne compte pas.

### Jamais sur un équipement réseau

- Une action corrective sur un routeur, un commutateur ou une borne Wi-Fi est refusée côté serveur,
  au déclenchement automatique comme à la demande manuelle. Aucun agent n'y tourne, mais la règle est
  écrite plutôt que supposée : un équipement réseau mal redémarré coupe tout ce qui est derrière.
- Restent interdits, par construction (ADR 0050) : commande libre (seul un nom circule, refusé par
  l'agent s'il est inconnu) et enchaînement de commandes (aucun interpréteur : `;`, `&&` ou `|` ne
  sont que des arguments).

### Catalogue d'actions et déploiement

- `agent.conf.example` (Linux et Windows) contient un **catalogue commenté, désactivé** :
  redémarrer nginx, apache2, php-fpm, recharger nginx, faire tourner les journaux, nettoyer un dossier
  temporaire d'application ; sous Windows, relancer le spouleur, IIS, nettoyer un dossier temporaire.
  Chaque action Linux privilégiée est donnée avec sa **ligne sudoers exacte** (commande complète, sans
  joker) ; chaque action Windows avec le droit précis à accorder au compte de l'agent sur le service
  (`sc.exe sdset`), jamais l'administration de la machine.
- Le complément systemd `actions-privileges.conf` (livré dans le paquet, **non installé par
  défaut**) rouvre seulement ce qu'il faut à sudo : `NoNewPrivileges=no`, ensemble de capacités
  complet, écriture dans `/var/log`, `/var/cache`, `/var/tmp` et `/run`. Une machine sans action
  garde le durcissement complet.
- Le rôle Ansible déploie les actions d'un groupe (`trans_dic1_agent_actions`) : section `[actions]`
  et `[action_descriptions]` dans un bloc marqué, règle sudo par commande exacte validée par
  `visudo -cf` avant d'être posée, complément systemd seulement si une action passe par sudo. Les noms
  et la forme des commandes sont vérifiés avant tout changement.

### Redémarrage forcé d'une VM par l'hyperviseur

- **Désactivé par défaut**, connecteur par connecteur (`vm_actions_enabled`). Il faut un **second
  jeton**, distinct du jeton de lecture (identifiant et secret différents, vérifiés), par sa seule
  référence `env:NOM`, au seul droit `VM.PowerMgmt` sous Proxmox.
- Seule action possible : `RESET` (l'équivalent du bouton « reset » de la console, pour une VM figée
  qui ne s'arrête plus proprement), sur une **VM** présente — jamais un hôte, jamais un conteneur.
  Appel `POST /nodes/{nœud}/qemu/{vmid}/status/reset`, identifiants vérifiés par motif avant de
  construire l'adresse.
- **Toujours sur approbation** : un administrateur fait une demande motivée ; un **autre**
  administrateur l'approuve (le demandeur ne peut pas s'approuver lui-même), et l'ordre part alors,
  la demande verrouillée en base pour qu'une double approbation ne redémarre pas deux fois. Une
  demande non décidée expire (60 minutes par défaut) ; une seule demande en attente par VM. Jamais de
  déclenchement automatique par une alerte.
- Chaque étape est auditée (`HYPERVISOR_VM_RESET_REQUESTED`, `…_SUCCEEDED`, `…_FAILED`,
  `…_REJECTED`, `…_EXPIRED`), sans le secret.

## Conséquences

- Migration V64 : colonnes de vérification et de limite sur `remediation_policy`, verdict et raison
  d'approbation sur `remediation_job`, second jeton sur `hypervisor_connector`, table
  `hypervisor_action`. Aucune migration existante modifiée.
- API : `verifyMinutes`, `maxAutoRuns`, `windowHours` dans la politique ; `verifyDueAt`,
  `verification`, `verifiedAt`, `approvalReason` dans l'exécution ; `identifiantJetonActions`,
  `referenceSecretActions`, `actionsVmActives` dans le connecteur ; `GET
  /api/v1/hyperviseurs/{id}/actions-vm`, `POST …/{id}/ressources/{ressourceId}/redemarrage`, `POST
  …/actions-vm/{actionId}/approbation` et `…/refus` (administrateurs).
- Interface : formulaire de politique (vérification, limite), verdict et raison dans le panneau de
  l'alerte, second jeton et demandes de redémarrage dans la page « Hyperviseurs ».
- Réglages : `REMEDIATION_VERIFY_SCAN_MS`, `COLLECTION_HYPERVISOR_VM_ACTIONS_APPROVAL_TTL_MINUTES`,
  `COLLECTION_HYPERVISOR_VM_ACTIONS_EXPIRY_SCAN_MS`.

## Limites

- **Jamais essayé sur une machine réelle** du parc ni contre un Proxmox réel : le redémarrage de VM
  est éprouvé contre un faux serveur HTTPS et un double de l'API ; le droit `VM.PowerMgmt` seul n'a pas
  été vérifié sur un cluster.
- Le complément systemd et les règles sudo n'ont pas été essayés **sous systemd** (le poste de
  développement n'en a pas) : la syntaxe, `visudo -cf` et la logique du rôle sont vérifiées, pas
  l'exécution d'une action privilégiée par le service durci.
- La vérification juge sur l'alerte : une alerte résolue pour une autre raison (maintenance, retour
  spontané) rend la correction « efficace » à tort ; une alerte qui met plus que le délai à retomber
  (confirmation sur plusieurs mesures) la rend « inefficace » à tort. Le délai doit couvrir la
  confirmation de la règle.
- Le rappel d'astreinte respecte la sévérité minimale : une correction inefficace sur une alerte
  d'information ne réveille personne.
- La règle des quatre yeux pour le redémarrage de VM suppose au moins deux administrateurs ayant le
  site dans leur périmètre ; un site géré par une seule personne ne peut pas redémarrer de VM depuis
  la plateforme (choix assumé, à confirmer par le CRI).
- vSphere et Hyper-V n'ont pas d'implémentation du redémarrage (le port renvoie « non pris en
  charge »).
