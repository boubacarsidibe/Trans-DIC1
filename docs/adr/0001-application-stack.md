# ADR-0001 — Stack applicative Trans-DIC1

- Statut : acceptée
- Date : 2026-10-09
- Décision liée : #2

## Contexte

Trans-DIC1 supervise les équipements réseau et serveurs de l'EPT. Le socle doit rester exploitable dans un réseau interne, éviter l'exposition de la topologie, supporter la collecte par agent Trans-DIC1 ou SNMP et fournir des builds reproductibles.

## Décision

| Domaine | Choix fixé |
| --- | --- |
| Backend | Java 17, Spring Boot 4.1.1, Maven Wrapper |
| Frontend | Node.js 24 LTS, npm 11.11.1, React 19, Vite 8 |
| Interface | React Router 7, Axios 1, Tailwind CSS 4 |
| Données | PostgreSQL 16 natif, migrations Flyway |
| Tests | JUnit/Spring Boot Test et H2 isolé ; Vitest/Testing Library |
| Exécution locale | Services natifs Java, Node.js et PostgreSQL, sans virtualisation |

Le dépôt reste un monorepo : `backend/` porte l'API et les modules métier, `frontend/` l'interface, et les adaptateurs de collecte restent derrière des ports applicatifs afin de ne pas coupler le métier à l'agent Trans-DIC1 ou à SNMP. Chaque module backend sépare `controller`, `service`, `repository`, `domain` et `dto`.

## Contrats

- Les endpoints métier sont versionnés sous `/api/v1`.
- Les erreurs utiliseront `application/problem+json` avec un code stable, un titre, un statut HTTP, une description et un identifiant de corrélation.
- Le client web centralise l'URL d'API et l'en-tête d'authentification.
- L'endpoint `/actuator/health` n'expose pas les détails internes au public.

## Sécurité et rôles

L'authentification cible repose sur des jetons JWT courts (15 minutes) et un mécanisme de renouvellement révocable (8 heures maximum). Les rôles initiaux sont `ADMIN`, `NOC_OPERATOR` et `VIEWER`. Chaque connexion, mutation d'inventaire, acquittement/résolution d'alerte et changement de rôle doit produire une entrée d'audit horodatée et corrélée. Les communautés SNMP, tokens, mots de passe et adresses internes ne doivent jamais être placés dans Git ni dans les journaux.

## Métriques et rétention

PostgreSQL constitue le stockage initial. Les métriques brutes sont conservées 30 jours, les agrégats horaires 13 mois et les agrégats journaliers 3 ans. Le partitionnement temporel et la purge automatisée seront introduits avec le module de collecte. Cette stratégie sera réévaluée à partir du volume observé avant toute adoption d'une extension spécialisée.

## Contraintes d'exploitation

- Les collectes SNMP privilégient SNMPv3 ; SNMPv2c reste une compatibilité explicitement autorisée par équipement.
- Les appels à l'agent Trans-DIC1 sont effectués côté serveur avec authentification, délais, reprises bornées et limitation de débit.
- Les collecteurs doivent fonctionner sans accès Internet et ne publier aucune donnée de topologie.
- Les images et dépendances doivent pouvoir être mises en cache dans l'infrastructure EPT.
- Le développement local ne dépend pas de Docker. Le mode de déploiement cible sera fixé après validation de l'hébergement EPT.

## Conséquences

Les versions et frontières sont désormais explicites. Cette ADR constitue la décision de référence ; toute modification structurante devra être portée par une nouvelle ADR.
