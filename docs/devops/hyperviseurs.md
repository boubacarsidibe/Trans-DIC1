# Connecteur d'hyperviseur : voir les VM sans agent

Un connecteur interroge l'API d'un hyperviseur, en lecture seule, et en tire l'inventaire des hôtes et des machines virtuelles, le lien VM → hôte, leurs mesures et leurs alertes, sans rien installer dans les VM. Le choix est expliqué dans l'[ADR-0055](../adr/0055-connecteur-hyperviseur.md).

Proxmox VE est disponible. VMware vSphere et Hyper-V sont prévus (interface et squelettes documentés dans `backend/.../collection/hypervisor/`), pas encore écrits.

## 1. Préparer Proxmox (une fois par cluster)

Sur un nœud du cluster, en root :

```sh
pveum user add supervision@pve --comment "Supervision Trans-DIC1 (lecture seule)"
pveum acl modify / --users supervision@pve --roles PVEAuditor
pveum user token add supervision@pve trans-dic1 --privsep 0
```

La dernière commande affiche le **secret du jeton** une seule fois. Il ne va pas dans la plateforme : il va dans l'environnement du serveur Trans-DIC1, par exemple dans le fichier d'environnement du service :

```text
PVE_CAMPUS_TOKEN=<secret affiché par pveum>
```

puis redémarrer le backend. Dans la plateforme, on ne saisira que la **référence** `env:PVE_CAMPUS_TOKEN`.

Facultatif — pour que l'adresse IP des VM soit connue (et qu'une sonde ICMP soit préparée) : installer QEMU Guest Agent dans les VM et donner en plus le droit de le lire (`VM.Monitor` en Proxmox VE 8, `VM.GuestAgent.Audit` en Proxmox VE 9) par un rôle personnalisé. Sans lui, tout fonctionne, sauf les adresses.

Certificat : Proxmox utilise par défaut un certificat signé par sa propre autorité. Récupérer `/etc/pve/pve-root-ca.pem` (public) pour le coller dans le connecteur. Le certificat et le nom d'hôte sont toujours vérifiés : l'adresse de l'API doit être un nom ou une adresse présent dans le certificat du nœud.

## 2. Créer le connecteur

**Parc → Hyperviseurs → Ajouter un connecteur** (administrateurs) :

| Champ | Exemple | Rôle |
| --- | --- | --- |
| Nom | Cluster du campus | libellé |
| Hyperviseur | Proxmox VE | famille ; les autres sont grisées tant qu'elles ne sont pas disponibles |
| Site | Salle serveurs | où ranger les équipements découverts (périmètre d'accès) |
| Adresse de l'API | `https://pve-01.example:8006` | HTTPS, sans chemin ; la garde anti-SSRF refuse boucle locale et lien local |
| Identifiant du jeton | `supervision@pve!trans-dic1` | utilisateur@domaine!nom |
| Référence du secret | `env:PVE_CAMPUS_TOKEN` | jamais le secret lui-même |
| Autorité interne | contenu de `pve-root-ca.pem` | facultatif ; vide, seules les autorités du système sont acceptées |
| Intervalle | 300 s | entre 30 et 3 600 s |
| Sondes ICMP | coché | une sonde **inactive** par VM dont l'adresse est connue |

Puis **Essayer la connexion** (lit l'inventaire sans rien créer) et **Synchroniser** (premier passage immédiat). Les passages suivants se font seuls, à l'intervalle choisi.

## 3. Ce qui apparaît

- Un équipement par hôte (type serveur) et par VM ou conteneur (type machine virtuelle), retrouvé à chaque passage sans doublon. Un serveur déjà inventorié à la même adresse dans le site est repris.
- Le lien VM → hôte dans la **Topologie**, déplacé quand la VM migre.
- Les mesures `hv.power` (1 allumée, 0 éteinte), `hv.cpu.usage`, `hv.memory.usage`, `hv.disk.usage`, `hv.net.in`, `hv.net.out` (octets par seconde) et `hv.uptime` dans l'historique de chaque équipement. Elles ne se mélangent pas à celles d'un agent installé dans la même VM.
- Des alertes de collecte : VM éteinte après avoir tourné, hôte hors ligne (les alertes de ses VM sont rangées dessous, un seul incident), hyperviseur injoignable trois passages de suite.
- Le bouton **Ressources** liste tout ce que voit le connecteur, y compris les VM **supprimées de l'hyperviseur** : elles restent dans l'inventaire avec leur historique.

## 4. Réglages du serveur

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `COLLECTION_HYPERVISOR_TICK_MS` | 30000 | fréquence à laquelle on regarde quels connecteurs sont dus |
| `COLLECTION_HYPERVISOR_TIMEOUT_SECONDS` | 15 | délai d'un appel à l'API |
| `COLLECTION_HYPERVISOR_FAILURE_THRESHOLD` | 3 | passages sans réponse avant l'alerte sur les hôtes |
| `COLLECTION_HYPERVISOR_ADDRESS_LOOKUPS_PER_RUN` | 50 | lectures d'adresse par l'agent invité au plus par passage |

## 5. Dépannage

| État affiché du connecteur | Cause | Action |
| --- | --- | --- |
| « Jeton refusé par Proxmox (HTTP 401) » | identifiant ou secret faux, jeton supprimé | vérifier l'identifiant ; recréer le jeton et mettre à jour la variable d'environnement |
| « Droits insuffisants (HTTP 403) : rôle PVEAuditor attendu » | rôle absent sur `/` | `pveum acl modify / --users supervision@pve --roles PVEAuditor` |
| « Certificat de … refusé » | autorité interne absente ou nom absent du certificat | coller `pve-root-ca.pem` ; utiliser dans l'adresse le nom du certificat du nœud |
| « Secret introuvable pour la référence env:… » | variable absente de l'environnement du serveur | l'ajouter, redémarrer le backend |
| « Cible interdite » | adresse en boucle locale ou lien local | donner l'adresse réseau du nœud |
| « … injoignable », « Délai dépassé » | réseau, pare-feu (port 8006), nœud arrêté | vérifier l'accès depuis le serveur Trans-DIC1 ; après trois échecs, les hôtes passent en alerte |
| VM sans adresse | agent invité absent ou droit manquant | installer QEMU Guest Agent, ajouter le droit de le lire |

## 6. Ce qui n'a pas été vérifié

Le connecteur n'a jamais été essayé contre un Proxmox réel : il l'est contre un faux serveur HTTPS qui rejoue des réponses écrites d'après la documentation de l'API (voir l'ADR, section Limites). À éprouver sur un cluster du CRI avant de s'y fier.
