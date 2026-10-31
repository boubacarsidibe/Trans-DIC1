# Déploiement Kubernetes avec Helm (OPS-03)

Le chart [`deploy/helm/trans-dic1`](../../deploy/helm/trans-dic1) déploie le backend, le frontend,
l'Ingress et, pour un cluster de test, une base PostgreSQL. La cible de production reste Docker
Compose (US23) tant que le CRI n'exploite pas de cluster ; le chart prépare cette évolution.

## Contenu

| Objet | Détail |
|---|---|
| `Deployment` backend | Image `<registre>/backend:<étiquette>`, sondes `startup`/`liveness` sur `/actuator/health/liveness`, `readiness` sur `/actuator/health/readiness`, utilisateur 10001, système de fichiers en lecture seule, toutes capacités retirées. |
| `HorizontalPodAutoscaler` | 2 à 4 répliques sur 70 % de CPU (désactivé en staging). |
| `PodDisruptionBudget` | Au moins un backend disponible pendant une maintenance du nœud. |
| `Deployment` frontend | Nginx non privilégié, sondes HTTP sur `/`. |
| `Ingress` | Même origine pour `/api` et `/` ; délai long et sans tampon pour le flux temps réel (SSE). |
| `StatefulSet` PostgreSQL | Seulement si `postgres.enabled=true` (test). En production : base externe sauvegardée. |

## Secrets

Aucun secret dans les valeurs du chart. Il attend un `Secret` existant (`existingSecret`) avec
`DATABASE_PASSWORD`, `JWT_SECRET` et, si besoin, `BOOTSTRAP_ADMIN_PASSWORD`, `SMTP_PASSWORD`,
`METRICS_SCRAPE_TOKEN`.

```bash
kubectl create namespace trans-dic1
kubectl -n trans-dic1 create secret generic trans-dic1-secrets \
  --from-literal=DATABASE_PASSWORD='…' --from-literal=JWT_SECRET='…(32 caractères au moins)'
```

Avec un gestionnaire externe (Vault, gestionnaire du CRI), un `ExternalSecret` de l'External
Secrets Operator produit ce même `Secret` : le chart n'a rien à changer.

## Installation

```bash
helm upgrade --install trans-dic1 deploy/helm/trans-dic1 -n trans-dic1 \
  -f deploy/helm/trans-dic1/values-staging.yaml \
  --set image.registry=ghcr.io/ept/trans-dic1 --set image.tag=<étiquette>
# Retour arrière : helm rollback trans-dic1 <révision> -n trans-dic1
```

## Dimensionnement

| Composant | Demande | Limite | Répliques |
|---|---|---|---|
| Backend | 0,25 CPU, 512 Mo | 1 CPU, 1 Go | 2 à 4 (HPA) |
| Frontend | 0,05 CPU, 64 Mo | 0,25 CPU, 128 Mo | 2 |
| PostgreSQL (test) | 0,1 CPU, 256 Mo | 0,5 CPU, 512 Mo | 1, volume 10 Go |

Ordre de grandeur pour le campus (quelques centaines d'équipements, une mesure par minute et par
indicateur) : un backend suffit en charge ; la seconde réplique sert la disponibilité.

**Plusieurs répliques du backend** :
- les tâches planifiées (collecte, escalade, anomalies, envois) sont protégées par le verrou en
  base `JobGuard` : une seule réplique les exécute ;
- le flux temps réel (SSE) part de la réplique qui a traité le changement. Avec plusieurs
  répliques, activer le relais par broker (ARCH-10, `docs/adr/0016-broker.md`) pour que chaque
  réplique relaie les événements à ses clients.

## Validation sur un cluster local

Procédure suivie avec minikube et Docker Desktop (voir le compte rendu dans la Pull Request) :

```bash
minikube start --cpus 4 --memory 6g
minikube addons enable ingress
eval $(minikube docker-env)   # construire directement dans le démon de minikube
IMAGE_TAG=local docker compose -f docker-compose.prod.yml --env-file deploy/pipeline.env build backend frontend
kubectl create namespace trans-dic1
kubectl -n trans-dic1 create secret generic trans-dic1-secrets \
  --from-literal=DATABASE_PASSWORD=test-local --from-literal=JWT_SECRET=test-local-test-local-test-local-1234
helm upgrade --install trans-dic1 deploy/helm/trans-dic1 -n trans-dic1 \
  -f deploy/helm/trans-dic1/values-staging.yaml --set image.tag=local --set image.pullPolicy=Never \
  --set ingress.tlsSecret= --set ingress.host=trans-dic1.local
kubectl -n trans-dic1 rollout status deploy/trans-dic1-backend --timeout=10m
```
