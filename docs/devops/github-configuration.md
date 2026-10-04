# Configuration GitHub à appliquer

Ces opérations nécessitent le repository distant et des droits d'administration.

## Protection de `main`

Dans **Settings → Rules → Rulesets**, créer une règle ciblant `main` :

1. interdire suppression et force-push ;
2. exiger une Pull Request ;
3. exiger une approbation si plusieurs mainteneurs sont disponibles ;
4. invalider les approbations après nouveau commit ;
5. exiger la résolution des conversations ;
6. exiger les checks CI réellement présents ;
7. exiger une branche à jour avant merge lorsque la cadence le permet ;
8. limiter les contournements aux administrateurs responsables des incidents.

## Protection de `develop`

Exiger une Pull Request, la résolution des conversations et les checks CI. Une approbation peut rester facultative pendant la phase initiale si un seul mainteneur travaille sur le projet.

## Paramètres du repository

- activer la suppression automatique des branches mergées ;
- activer les alertes Dependabot et les mises à jour de sécurité ;
- activer Secret Scanning et Push Protection si disponibles ;
- activer Private Vulnerability Reporting avant de publier un canal de signalement ;
- autoriser uniquement les stratégies de merge retenues par l'équipe.

## Labels

Créer : `frontend`, `backend`, `devops`, `monitoring`, `snmp`, `security`, `database`, `documentation`, `bug`, `feature`, `priority:low`, `priority:medium`, `priority:high`, `priority:critical`.

## GitHub Project

Colonnes recommandées : `Backlog`, `Ready`, `In Progress`, `Review`, `Testing`, `Done`.

## CODEOWNERS

Ne créer ce fichier qu'après identification des comptes ou équipes réels. Couvrir ensuite les futurs dossiers applicatifs, `infra/`, `docs/` et `.github/` sans utiliser de propriétaires fictifs.

