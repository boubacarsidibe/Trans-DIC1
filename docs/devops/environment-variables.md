# Variables d'environnement

## Règles

- `.env.example` décrit les noms et valeurs non sensibles autorisées.
- `.env` est local, ignoré par Git et ne doit jamais être joint à une Issue.
- Les secrets CI/CD sont stockés dans GitHub Actions Secrets.
- Les secrets staging et production proviennent du gestionnaire de secrets de la cible, jamais d'une image Docker.
- Une variable ajoutée au code doit être ajoutée au modèle et documentée dans la même PR.

## Contrat initial

| Variable | Sensible | Environnements | Description |
| --- | --- | --- | --- |
| `APP_ENV` | Non | Tous | `development`, `testing`, `staging` ou `production` |
| `APP_HOST` | Non | Local | Interface d'écoute ou hôte local |
| `APP_PORT` | Non | Tous | Port, à fixer avec le premier service |
| `DATABASE_HOST` | Selon topologie | Tous | Hôte de la base |
| `DATABASE_PORT` | Non | Tous | Port de la base |
| `DATABASE_NAME` | Non | Tous | Nom logique de la base |
| `DATABASE_USER` | Oui en pratique | Tous | Compte à privilèges minimaux |
| `DATABASE_PASSWORD` | Oui | Tous | Mot de passe de la base |
| `JWT_SECRET` | Oui | Tous | Secret de signature si JWT retenu |
| `SNMP_COMMUNITY` | Oui | Selon collecte | Communauté SNMP v1/v2c ; SNMPv3 est préférable |
| `SNMPV3_USERNAME` | Oui | Selon collecte | Identité SNMPv3 |
| `SNMPV3_AUTH_PASSWORD` | Oui | Selon collecte | Secret d'authentification SNMPv3 |
| `SNMPV3_PRIV_PASSWORD` | Oui | Selon collecte | Secret de chiffrement SNMPv3 |
| `SMTP_*` | Variable | Selon notification | Configuration email |
| `WEBHOOK_URL` | Oui | Selon notification | URL de notification signée ou secrète |

Les variables non retenues par la stack devront être supprimées du contrat plutôt que laissées inactives.

## Différences d'environnement

| Environnement | Données | Secrets | Exposition |
| --- | --- | --- | --- |
| Development | Synthétiques | Locaux, non partagés | Poste développeur |
| Testing | Éphémères | Dédiés aux tests | CI isolée |
| Staging | Assainies | Coffre staging | Réseau contrôlé |
| Production | Réelles | Coffre production | Accès minimal et audité |

