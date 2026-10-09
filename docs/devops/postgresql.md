# PostgreSQL local sans Docker

Le développement cible PostgreSQL 16 natif. Sur Windows, l'installateur PostgreSQL crée normalement le service `postgresql-x64-16`. Aucun conteneur ni mécanisme de virtualisation n'est requis.

## Vérifier et démarrer le service

Dans un terminal PowerShell ouvert en administrateur si le service est arrêté :

```powershell
Get-Service postgresql-x64-16
Start-Service postgresql-x64-16
pwsh ./scripts/check-local-postgresql.ps1
```

Le contrôle utilise `pg_isready` et le port `5432` par défaut. Un autre port peut être vérifié avec `-Port 5433`.

## Initialiser la base

Ouvrez `psql` ou pgAdmin avec le compte administrateur défini lors de l'installation, puis exécutez :

```sql
CREATE ROLE trans_dic1 LOGIN PASSWORD '<mot-de-passe-local>';
CREATE DATABASE trans_dic1 OWNER trans_dic1;
```

Copiez ensuite `.env.example` vers `.env` et placez le mot de passe uniquement dans `DATABASE_PASSWORD`. Le fichier `.env` est ignoré par Git.

## Démarrer le backend

Chargez les variables de `.env` dans votre terminal, puis :

```powershell
Set-Location backend
.\mvnw.cmd spring-boot:run
```

Le healthcheck applicatif `GET http://localhost:8080/actuator/health` vérifie notamment la connexion JDBC sans publier les détails internes.

## Base de tests

Les tests backend utilisent H2 en mémoire, en mode de compatibilité PostgreSQL. Cette base isolée est recréée à chaque exécution et applique les migrations Flyway.

## Jeu de données du Sprint 0

Flyway crée automatiquement cinq équipements de démonstration dans la table
`equipment`. Ils couvrent cinq types et cinq états différents. Leurs codes
commencent par `DEMO-`, leurs sites par `LAB-FICTIF-` et leurs adresses utilisent
exclusivement les réseaux documentaires RFC 5737 (`192.0.2.0/24`,
`198.51.100.0/24` et `203.0.113.0/24`) : aucune cible EPT réelle n'est incluse.

Le chargement est reproductible et idempotent. Flyway n'exécute une migration
versionnée qu'une fois et chaque insertion vérifie également le code inventaire
avant d'ajouter la ligne. Pour contrôler le résultat :

```sql
SELECT inventory_code, name, equipment_type, management_address,
       operational_status, site
FROM equipment
ORDER BY inventory_code;
```
