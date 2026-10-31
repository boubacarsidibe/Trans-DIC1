# ADR-0011 — Déploiement : Compose de production derrière un reverse proxy Nginx, retour arrière par étiquette

- Statut : acceptée ; `docker-compose.prod.yml`, reverse proxy et `deploy/deploy.sh` implémentés
- Date : 2026-11-01
- Précise : diagramme `docs/architecture/diagrams/07-deployment/deployment-target.mmd` ; s'appuie sur ADR-0006 (agents actifs), ADR-0009 (sauvegarde) et ADR-0010 (observabilité)

## Contexte

US23 demande de déployer la plateforme selon le diagramme cible : reverse proxy, interface, API, collecteur et PostgreSQL. Il faut des healthchecks, des volumes explicites, des utilisateurs non root, aucun secret dans les images et un retour arrière documenté. La cible d'hébergement de l'EPT n'est pas encore connue : la solution doit tourner sur un seul serveur Linux avec Docker, sans dépendre d'un orchestrateur.

## Options

| Option | Avantages | Inconvénients |
| --- | --- | --- |
| A. Docker Compose sur un serveur, reverse proxy Nginx | Même outil qu'en développement ; un seul fichier versionné ; adapté à quelques centaines d'équipements | Pas de haute disponibilité : un serveur unique |
| B. Kubernetes avec Helm | Haute disponibilité, mises à jour progressives | Disproportionné pour le MVP ; prévu en évolution (OPS-03) |
| C. Installation native (systemd, Nginx du système) | Pas de Docker | Environnement différent du développement ; mises à jour et retour arrière manuels |

## Décision

1. Retenir **l'option A** : `docker-compose.prod.yml`, distinct du Compose de développement, avec un nom de projet propre (`trans-dic1-prod`) pour ne jamais partager ses volumes.
2. **Un seul point d'entrée** : le reverse proxy Nginx (image sans privilèges) termine TLS, redirige HTTP vers HTTPS et pose HSTS. Il sert l'interface et transmet `/api/` au backend, sans mise en tampon pour le flux SSE. De l'extérieur, il n'expose que `/actuator/health`.
3. **Même origine** : l'interface est construite avec `VITE_API_BASE_URL=/api/v1`. Le cookie de renouvellement (`SameSite=Strict`, ADR-0005) fonctionne sans CORS.
4. **Adresse du client** : le proxy remplace `X-Forwarded-For`, et le backend le lit avec `server.forward-headers-strategy=native`. La limitation des connexions par adresse reste ainsi efficace derrière le proxy. Cette lecture reste désactivée quand l'API est exposée directement, car un client pourrait sinon choisir son adresse.
5. **Isolation** : deux réseaux.
   - `front` relie le proxy, l'interface et le backend ; il donne aussi au backend l'accès sortant vers les équipements, le SMTP et les canaux de notification.
   - `back`, sans accès extérieur, relie le backend, PostgreSQL et la sauvegarde. PostgreSQL ne publie aucun port.
6. **Durcissement** : tous les services applicatifs tournent sans root, avec `no-new-privileges` et `cap_drop: ALL`. Le système de fichiers est en lecture seule (`/tmp` en mémoire), et les logs Docker tournent (10 Mo × 5).
7. **Le collecteur** reste le module `collection` de l'API (monolithe modulaire, ADR-0006) : il n'y a pas de service séparé tant que la charge ne le justifie pas. Les agents envoient leurs mesures par le proxy.
8. **Versions et retour arrière** : les images sont repérées par `IMAGE_TAG`. `deploy/deploy.sh` procède en quatre étapes :
   - il sauvegarde la base ;
   - il démarre la nouvelle version et attend les healthchecks ;
   - il la contrôle par le proxy (`deploy/smoke-test.sh`) ;
   - en cas d'échec, il redémarre automatiquement l'étiquette précédente.

   Les données ne sont jamais restaurées automatiquement : c'est une décision humaine (runbook d'ADR-0009).

## Conséquences

- Les secrets restent dans `.env.prod` sur le serveur (droits 600). Le contrôle de déploiement vérifie qu'aucune valeur secrète n'apparaît dans les variables ni dans l'historique des images.
- Le certificat TLS est fourni par le CRI et monté en lecture seule. Un certificat auto-signé suffit pour les tests.
- Sans registre d'images, le serveur construit les images lui-même (`--build`) ou charge des archives (`--local`). La publication dans un registre relève d'OPS-01.
- Le passage en staging, puis en production, dépend de la mise à disposition d'un serveur par le CRI.
