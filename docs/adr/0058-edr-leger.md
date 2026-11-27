# ADR 0058 : EDR léger dans l'agent

- Statut : accepté
- Date : 2026-11-27
- Complète l'[ADR 0028](0028-supervision-securite.md) (sécurité des journaux), l'[ADR 0048](0048-reperes-mitre-attack.md) (repères MITRE ATT&CK) et l'[ADR 0056](0056-services-et-cause-racine.md) (services découverts) ; respecte l'[ADR 0018](0018-perimetre-par-site.md)

## Contexte

La plateforme voit les attaques **qui laissent une trace dans les journaux** (injections web, force brute, ADR 0028) ou **dans le trafic** (balayages, ADR 0033). Elle ne voit pas ce qu'un attaquant fait **une fois entré** sur une machine : lancer un mineur de cryptomonnaie depuis `/tmp`, ajouter une tâche cron pour revenir après un redémarrage, créer un compte administrateur, modifier le fichier `hosts`, ouvrir une connexion vers sa machine de commande. C'est le rôle d'un EDR (*Endpoint Detection and Response*).

## Options

1. **Intégrer un EDR du marché** (Microsoft Defender for Endpoint, CrowdStrike, Wazuh). Surveillance depuis le noyau, bien plus complète, mais un produit de plus à acheter ou à exploiter, et des alertes hors de la plateforme.
2. **Recevoir les alertes d'un EDR existant** par syslog ou webhook. Utile si l'école en a un ; ne règle rien si elle n'en a pas.
3. **Un EDR léger dans notre agent** : l'agent, déjà installé et sans privilège, décrit périodiquement ce qu'un attaquant modifie ; le serveur compare à une ligne de base et à des indicateurs connus.

## Décision

Option 3, sans exclure la 2 : le récepteur syslog (ADR 0021) accepte déjà les alertes d'un EDR ou d'un SIEM.

### Ce que l'agent envoie (2.3.0)

Toutes les 5 minutes, avec l'instantané, `POST /api/v1/agent/securite-poste` :

| Section | Linux | Windows |
| --- | --- | --- |
| Programmes | nom et chemin de l'exécutable (`/proc/<pid>/exe`, à défaut premier mot de la ligne de commande s'il est un chemin) | nom et chemin (`QueryFullProcessImageNameW`) |
| Démarrages automatiques | unités systemd, `/etc/crontab`, `/etc/cron.*`, `rc.local`, `/etc/profile.d`, avec leur empreinte | clés `Run` / `RunOnce` (HKLM et HKCU), dossiers de démarrage, services **installés** |
| Comptes | comptes d'UID 0, membres de `sudo` / `wheel` / `admin`, comptes interactifs | membres du groupe Administrateurs (retrouvé par son SID, quelle que soit la langue) |
| Fichiers sensibles | empreinte de `passwd`, `group`, `hosts`, `resolv.conf`, `sshd_config`, `crontab`, présence de `ld.so.preload` | empreinte du fichier `hosts` |
| Connexions | connexions TCP sortantes établies (`/proc/net/tcp`) | idem (`GetExtendedTcpTable`) |

Ce que l'agent **n'envoie jamais** : les arguments d'une commande (ils contiennent souvent des mots de passe), le contenu d'un fichier (seulement son empreinte SHA-256), un mot de passe ou un hachage. Il ne fait que lire, sans privilège ; ce qu'il ne peut pas lire est absent et le rapport est marqué partiel. `edr = false` dans `agent.conf` coupe la télémétrie.

### Ce que le serveur en fait

- **Indicateurs**, jugés à chaque rapport : programme lancé depuis un dossier temporaire (T1204.002), outil offensif connu — mineur (T1496), vol d'identifiants (T1003), tunnel (T1572), balayage (T1046), reconnaissance d'annuaire (T1087) —, présence de `/etc/ld.so.preload` (T1574.006), second compte d'UID 0 (T1136.001), connexion vers un port de porte dérobée ou de minage (T1571).
- **Changements** par rapport à la ligne de base, à partir du deuxième rapport : nouveau démarrage automatique ou modifié — cron (T1053.003), systemd (T1543.002), service Windows (T1543.003), clé Run ou dossier de démarrage (T1547.001), script de session (T1546.004) —, nouveau compte (T1136.001) ou compte devenu privilégié (T1098), fichier sensible modifié (T1098, T1556, T1565.001 selon le fichier). **Le premier rapport ne signale aucun changement** : il fixe la référence.
- Chaque constat (`endpoint_finding`) ouvre une **alerte de sécurité** (`SECURITY`, clé `security:poste:`) avec la technique dans son message : astreinte, notifications et regroupement en incidents s'appliquent comme pour les autres alertes.
- **Un sujet, un constat** : un même constat revu à chaque rapport ne fait que mettre à jour sa date. Un opérateur le juge **légitime** (il ne revient plus) ou **malveillant** (il reste visible pour l'enquête) ; le jugement est audité. Un nouveau changement du même fichier a une autre empreinte, donc un autre sujet.
- Écran **Sécurité des postes** : postes surveillés, constats filtrés par état, lien vers la fiche MITRE de chaque technique. Le périmètre par site s'applique.

## Conséquences

- Migration V65 : `endpoint_profile`, `endpoint_baseline`, `endpoint_finding`.
- Agent 2.3.0 : module `edr.py`, option `edr`, trois appels Windows de plus (chemin des processus, table TCP complète, membres des Administrateurs).
- API : `POST /api/v1/agent/securite-poste` (agent), `GET /api/v1/securite/postes`, `POST /api/v1/securite/postes/constats/{id}/decision` (opérateurs NOC, administrateurs).

## Limites

- **Ce n'est pas un EDR du marché.** Pas de surveillance en temps réel depuis le noyau : un rapport toutes les 5 minutes, donc un programme qui s'exécute et disparaît entre deux rapports n'est pas vu. Pas d'analyse de la mémoire, pas de détection par comportement d'un processus, pas de réponse automatique (isoler une machine, tuer un processus).
- **Un attaquant qui a les droits administrateur peut tromper l'agent** (rootkit, agent arrêté). L'arrêt de l'agent se voit (alerte de collecte), la falsification fine non.
- **L'agent sans privilège ne lit pas tout** : sous Linux, le chemin des processus d'autres comptes n'est souvent connu que par la ligne de commande ; les crontabs des utilisateurs (`/var/spool/cron`) et les tâches planifiées Windows ne sont pas lues.
- La liste d'outils offensifs est reconnue par **nom** : un outil renommé échappe à cette règle (mais pas à celle des dossiers temporaires s'il s'y trouve).
- Les installations et mises à jour légitimes produisent des constats « nouveau démarrage automatique » : c'est le prix d'une ligne de base, d'où le jugement en un clic.
- Essayé sur un vrai poste Windows 11 ; la partie Linux est éprouvée sur des arborescences construites, pas sur un serveur réel du parc.
