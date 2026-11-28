# ADR 0060 : suivi automatique des services

- Statut : accepté
- Date : 2026-11-28
- Évolution demandée par les encadrants (supervision des services plus complète)
- Complète l'[ADR 0056](0056-services-et-cause-racine.md) sans en changer le principe ; s'appuie sur
  l'[ADR 0058](0058-moindre-privilege-des-roles.md) (suivre, ignorer, réactiver : administrateur)

## Contexte

L'ADR 0056 a posé le suivi des services en un clic : l'agent liste les services **démarrés**, un
administrateur en choisit un, l'agent remonte alors `service.up[nom]`. Deux manques :

1. **Ce qui ne tourne pas n'existe pas.** Un service arrêté n'apparaissait pas dans la liste : c'est
   pourtant lui qu'il faut voir. Et rien ne disait s'il **devait** tourner.
2. **Tout est à la main.** Sur cent machines, personne ne clique « Suivre » sur chaque nginx,
   PostgreSQL ou spouleur : les services importants restaient non suivis.

## Options

1. Suivre **tous** les services : sur un Windows, environ 300 services, dont deux tiers en démarrage
   manuel, normalement arrêtés. Du bruit et des fausses alertes.
2. Une liste de services « importants » maintenue à la main : à refaire pour chaque système.
3. **Laisser le système dire ce qui doit tourner** : un service en démarrage automatique (Linux
   `enabled`, Windows automatique ou automatique différé) est attendu en marche. C'est ce que
   l'exploitant a déjà décidé en configurant la machine.

## Décision

Option 3.

### Agent 2.3

L'instantané (toutes les 5 minutes) porte `serviceStates` : **tous** les services, démarrés ou non,
avec leur mode de démarrage (`AUTO`, `AUTO_DELAYED`, `MANUAL`, `DISABLED`, `UNKNOWN`).

- Linux : `systemctl list-unit-files --type=service` croisé avec `systemctl list-units --type=service
  --all`. `enabled` → automatique ; `disabled`, `static`, `indirect`, `generated` → manuel ;
  `masked` → désactivé ; l'instance d'un modèle (`getty@tty1`) n'a pas de fichier propre → inconnu.
- Windows : `EnumServicesStatusExW` (tous les états), puis `QueryServiceConfigW` et
  `QueryServiceConfig2W` (`SERVICE_CONFIG_DELAYED_AUTO_START_INFO`) par ctypes.

Les garde-fous de l'ADR 0056 restent : bibliothèque standard seulement, jamais de shell, lecture
seule, aucune commande venue de la plateforme, 300 services au plus (si la limite coupe, l'agent garde
d'abord les automatiques puis ceux qui tournent), noms validés, lecture en échec simplement absente.
`services` (les noms démarrés) reste envoyé pour une plateforme plus ancienne ; une plateforme à jour
accepte toujours un agent 2.2.

L'agent accepte désormais 200 services suivis (50 avant) et, sous Linux, lit leur état en une seule
commande `systemctl is-active` par paquet de 50 noms.

### Plateforme

- **Suivi d'office.** À chaque instantané, un service à démarrage automatique **vu démarré** est
  suivi (service `AGENT_SERVICE`, `auto_followed`), sauf s'il est exclu par
  `collection.services.exclude` ou ignoré. Option `COLLECTION_SERVICES_AUTO_FOLLOW`, active par
  défaut. Une seule entrée d'audit par lot (`AGENT_SERVICES_AUTO_FOLLOWED`).
- **Pas d'avalanche à la première découverte.** Un service automatique vu **arrêté** n'est pas suivi
  d'office : on ne connaît pas encore son histoire (beaucoup de services Windows démarrent sur un
  déclencheur puis s'arrêtent). Il est montré « arrêté anormalement » sur la fiche ; s'il démarre, il
  est suivi au relevé suivant. Une alerte ne vient donc que d'un arrêt **constaté après** le suivi,
  après le délai de confirmation de la règle.
- **Manuel ou désactivé : montré, pas suivi.** L'administrateur le suit en un clic.
- **Ignoré.** Un service dont l'administrateur cesse le suivi est inscrit dans
  `ignored_agent_service` et n'est plus jamais suivi d'office. Le suivre à nouveau le réactive
  (`AGENT_SERVICE_REACTIVATED`).
- **L'alerte.** Le moteur ne change pas : la règle générique « Service arrêté » (`service.up[*] < 1`,
  120 s, critique) s'applique. Pour un service suivi à démarrage automatique, le message devient « Le
  service X devrait tourner (démarrage automatique) mais il est arrêté ». Un service en démarrage
  manuel, suivi à la main, garde le message générique. Le mode de démarrage est tenu à jour à chaque
  instantané.
- **Fiche de l'équipement.** État, mode de démarrage, suivi (d'office, à la main, ignoré, non suivi),
  date du relevé ; filtres « tous », « suivis », « arrêtés anormalement ». Suivre, ignorer et
  réactiver sont réservés à l'administrateur (ADR 0058).
- Les services suivis, d'office ou non, restent des services `AGENT_SERVICE` : ils figurent dans les
  dépendances, la cause racine, l'analyse d'impact et les pages de statut comme avant.

## Conséquences

- Un serveur nouvellement équipé de l'agent 2.3 voit ses services importants suivis au premier
  instantané, sans clic, et sans alerte à ce moment-là.
- Une centaine de services suivis par machine Windows : la liste des dépendances s'allonge ; le
  volume de mesures reste faible (une valeur par service et par intervalle).
- Un service désinstallé reste suivi et passe arrêté : l'administrateur cesse de le suivre (il
  devient ignoré).

## Limites

- Le déclenchement Windows « sur événement » (trigger start) n'est pas lu : un tel service, s'il est
  aussi automatique, est suivi dès qu'il est vu démarré, et alertera quand il s'arrêtera normalement ;
  l'administrateur l'ignore.
- La date du relevé est celle du dernier instantané (5 minutes), pas celle de la dernière mesure
  `service.up`.
