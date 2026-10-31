# Chaîne de livraison (OPS-01)

Le compte GitHub étant bloqué pour facturation, les workflows GitHub Actions ont été retirés
(risque R2). La livraison repose donc sur des scripts exécutés sur le serveur de staging ; le même
enchaînement existe sous forme de workflow GitHub, inactif, dans
[`templates/delivery.yml`](templates/delivery.yml), à réactiver quand les Actions reviendront.

```text
commit sur develop ──► minuteur (5 min) ──► watch-develop.sh
                                              │
                                              ▼
                     pipeline.sh : tests ► images (étiquette = commit) ► Trivy ► registre
                                              │ (une faille CRITICAL corrigeable arrête tout)
                                              ▼
                     staging : deploy.sh --local, healthchecks, smoke-test, retour arrière seul
                                              │
                                              ▼
                     promote.sh <étiquette> : approbation nominative ► production (deploy.sh)
```

## Scripts

| Script | Rôle |
|---|---|
| `scripts/pipeline/pipeline.sh` | Tests backend et frontend, construction des quatre images (`backend`, `frontend`, `proxy`, `backup`) étiquetées par le hash du commit, analyse Trivy (`CRITICAL`, failles corrigeables), publication si `IMAGE_REGISTRY` est défini, déploiement en staging. Journal : `deploy/reports/pipeline-<étiquette>.log`. |
| `scripts/pipeline/watch-develop.sh` | Déclencheur : si `origin/develop` a avancé, lance la chaîne sur ce commit. Verrou contre les exécutions simultanées ; un commit en échec n'est pas relancé en boucle. |
| `scripts/pipeline/promote.sh <étiquette>` | Passage en production d'une version validée en staging, sur approbation nominative (saisie au terminal, ou `APPROVED_BY`). Chaque approbation est consignée dans `deploy/approvals.log`. |
| `deploy/deploy.sh` (US23) | Sauvegarde, démarrage, healthchecks, smoke-test, **retour arrière automatique** vers la version précédente. |

## Installation sur le serveur de staging

```bash
sudo useradd --system --create-home --groups docker transdic1
sudo -u transdic1 git clone https://github.com/boubacarsidibe/Trans-DIC1.git /opt/trans-dic1
# Fichiers propres au serveur, jamais versionnés :
#   /opt/trans-dic1/.env.staging         variables du staging (voir environment-variables.md)
#   /opt/trans-dic1/deploy/pipeline.secrets   IMAGE_REGISTRY et identifiants du registre si publication
sudo cp /opt/trans-dic1/deploy/systemd/trans-dic1-pipeline.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now trans-dic1-pipeline.timer
journalctl -u trans-dic1-pipeline.service -f
```

## Secrets

- Valeurs du staging et de la production : `.env.staging` et `.env.prod` sur les serveurs
  (ignorés par Git, lisibles du seul utilisateur de service).
- Identifiants du registre : `deploy/pipeline.secrets` (ignoré par Git), chargé par l'unité systemd.
- La construction des images n'utilise que des valeurs factices (`deploy/pipeline.env`) : aucune
  image ne contient de secret (vérifié par `deploy/smoke-test.sh`).

## Retour arrière : procédure testée

1. **Automatique** : si une version ne passe pas les healthchecks ou le smoke-test, `deploy.sh`
   redémarre la version précédente (étiquette lue dans `deploy/.deployed-tag` ou
   `deploy/.staging-tag`).
2. **Manuel**, vers une version antérieure connue :

   ```bash
   ENV_FILE=.env.prod deploy/deploy.sh <étiquette-précédente> --local
   ```

3. Les migrations Flyway ne s'annulent pas : une sauvegarde est faite avant chaque déploiement ;
   restauration dans [operations.md](operations.md).

Le retour arrière automatique est éprouvé en déployant volontairement une version qui ne démarre
pas (variable obligatoire absente) : le script rétablit la précédente et le smoke-test repasse.

## Ce qui reste à la main

- La création du registre (GHCR ou registre du CRI) et de ses identifiants.
- L'approbation de chaque passage en production (voulu).
