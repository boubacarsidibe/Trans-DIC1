# ADR-0026 — Page de statut des services, interne ou publique

- Statut : acceptée, implémentée
- Date : 2026-11-06
- Précise : ADR-0005 (jetons, cookie de renouvellement), ADR-0011 (reverse proxy, adresse du client), ADR-0018 (périmètre par site), ADR-0019 (sondes actives). Plan maître F2.3 ; « Pour dépasser les concurrents » n° 3 du [benchmark](../benchmark-et-plan-depassement.md).

## Contexte

Les usagers de l'école (étudiants, enseignants, administration) demandent « la messagerie marche-t-elle ? », « le Wi-Fi est-il coupé ? ». Trans-DIC1 connaît déjà la réponse (sondes, alertes, maintenances) mais ne la montre qu'aux comptes du NOC, avec des noms techniques. Uptime Kuma a des pages de statut mais ne supervise pas un parc ; Zabbix, LibreNMS, Grafana et Checkmk n'en ont pas en standard. Contraintes : aucune dépendance nouvelle ; ne rien ouvrir d'autre sans connexion ; ne jamais exposer un nom d'hôte, une adresse, un port, un nom d'équipement ni un identifiant interne ; tenir une rafale de visites sans charger la base.

## Options

| Option | Pour | Contre |
|---|---|---|
| A. Module `statuspage` interne : pages, composants rattachés à une source supervisée, incidents ; lecture publique en liste blanche | réutilise sondes, alertes et maintenances ; aucune dépendance ; un seul outil pour le NOC | une surface publique à défendre (limitation, cache, absence d'énumération) |
| B. Uptime Kuma à côté de Trans-DIC1 | pages de statut prêtes | un second outil, des sondes en double, aucun lien avec le parc, les alertes et les maintenances |
| C. Page statique publiée à la main (site de l'école) | aucune surface d'attaque côté API | jamais à jour pendant une panne, travail manuel |

**Décision : A.**

## Modèle (migration V35)

- `status_page` : `slug` unique (`[a-z0-9-]{3,60}`, vérifié aussi par la base), titre, description, visibilité `INTERNAL` ou `PUBLIC`, active. **10 pages au plus.**
- `status_component` : page, **nom affiché** choisi pour les usagers (« Messagerie »), groupe, position, source `PROBE` (service supervisé), `EQUIPMENT` ou `SITE` et son identifiant. Jamais de nom d'hôte libre : la source doit exister. **50 composants par page au plus.** Une source supprimée laisse le composant à l'état inconnu, signalé à l'administrateur (« source supprimée »).
- `status_incident` (titre, statut `INVESTIGATING`/`IDENTIFIED`/`MONITORING`/`RESOLVED`, impact `NONE`/`MINOR`/`MAJOR`, message ≤ 2000, composants touchés, dates, auteur) et `status_incident_update` (statut, message ≤ 2000, date, auteur). Texte brut seulement : les caractères `<` et `>` et les caractères de contrôle (hors retour à la ligne) sont refusés, l'interface affiche le texte sans l'interpréter (React échappe tout, `white-space: pre-wrap`).

## Calcul de l'état (déterministe, testé en matrice)

Évaluation pure (`ComponentStateEvaluator`), sur des faits lus par des requêtes SQL propres au module :

| Source | Règle |
|---|---|
| Toutes | maintenance planifiée ou en cours (ni annulée ni terminée) dont la fenêtre `[début, fin[` couvre l'instant, sur l'équipement ou son site : `MAINTENANCE` |
| Sonde | inactive, équipement non supervisé, jamais exécutée, résultat indéterminé ou périmé (plus de max(3 intervalles, 5 min)) : `UNKNOWN` ; en ligne : `OPERATIONAL` ; 3 échecs consécutifs ou plus : `MAJOR_OUTAGE` ; 1 ou 2 échecs (instable) : `DEGRADED` |
| Équipement | statut `UP` → `OPERATIONAL`, `DEGRADED` → `DEGRADED`, `DOWN` → `MAJOR_OUTAGE`, `UNKNOWN` → `UNKNOWN`, `MAINTENANCE` → `MAINTENANCE` ; non supervisé : `UNKNOWN` |
| Alertes actives de la source | alerte de collecte (injoignable) `MAJOR_OUTAGE`, `CRITICAL` `PARTIAL_OUTAGE`, `WARNING` `DEGRADED`, `INFO` sans effet ; pour une sonde : alertes de cette sonde et alertes sans service de son équipement. Le pire l'emporte ; une alerte rend mesurable un état inconnu |
| Site | agrégat de ses équipements supervisés, maintenance et inconnu ignorés : tous en panne majeure `MAJOR_OUTAGE`, au moins une panne `PARTIAL_OUTAGE`, au moins un dégradé `DEGRADED`, sinon `OPERATIONAL` |
| Page | la pire panne ; sans panne, `MAINTENANCE` si un composant y est, sinon `OPERATIONAL` si un composant est mesuré, sinon `UNKNOWN` |

**Disponibilité sur 90 jours** (jours UTC, `AvailabilityCalculator`) : moyenne des mesures `probe.up` (0 ou 100 %) du jour, comme le SLA exclut les maintenances : toute heure qui chevauche une maintenance (même terminée) de l'équipement ou de son site est retirée. Mesures brutes de préférence (par sonde, `service_id`), agrégats horaires ensuite (mesures brutes purgées après 30 jours). Les agrégats horaires mélangent toutes les sondes d'un équipement : pour un composant « sonde », ils ne servent que si l'équipement n'a qu'une sonde, sinon les jours sans mesure brute restent inconnus. Un équipement agrège ses sondes, un site ses équipements supervisés. **Un jour sans mesure est inconnu, jamais 100 %** ; arrondi par défaut (99,999 % s'affiche 99,99 %). Un équipement sans sonde n'a pas de disponibilité (la disponibilité par alertes de collecte reste celle du rapport SLA).

## Cache et charge

L'instantané d'une page (états, incidents) est gardé `statuspage.cache-seconds` (30 s), la disponibilité `statuspage.availability-cache-seconds` (5 min) ; la liste des slugs publics aussi (30 s), si bien qu'un slug inconnu ne coûte aucune requête. Un seul calcul par page et par période, même sous une rafale concurrente (verrou par page, testé avec 200 lectures simultanées). Toute écriture d'un administrateur invalide la page. Calcul borné : 50 composants, une requête groupée par type de source pour les états, au plus 90 × 24 lignes horaires par composant pour la disponibilité, 20 incidents de 14 jours et 20 mises à jour par incident dans le rendu.

## API

- `/api/v1/pages-statut` : liste et vue interne (`/{id}/vue`) pour tous les rôles, composants filtrés par le périmètre de sites (un composant dont la source est hors périmètre, ou disparue, est caché à un compte limité ; un incident lié seulement à des composants cachés aussi) ; administration ADMIN (pages, composants, incidents, mises à jour, recherche de sources), validée avec messages en français et tracée (`STATUS_PAGE_*`, `STATUS_COMPONENT_*`, `STATUS_INCIDENT_*`).
- `GET /api/v1/public/statut/{slug}` : **seul accès sans connexion à des données métier**, déclaré explicitement dans `SecurityConfig` pour la méthode GET seulement.

## Modèle de menace de la page publique

**Ce qui est exposé**, par une liste blanche stricte (record `StatusPageView`) : titre et description de la page, état global, pour chaque composant son nom affiché, son groupe, son état et sa disponibilité journalière (nombres), les incidents de 14 jours (titre, statut, impact, message, noms affichés des composants, mises à jour avec message et heure), l'heure du calcul. **Jamais** : nom d'hôte, adresse IP, nom ou code d'équipement, nom de sonde, port, identifiant d'alerte ou interne (aucun UUID), nom d'utilisateur. Le module ne lit aucune entité d'un autre module (règle ArchUnit) : rien ne peut fuir par sérialisation. Un test sérialise la réponse et vérifie l'absence des valeurs sensibles semées et de tout UUID.

**Énumération** : une page inconnue, interne, inactive ou un slug mal formé reçoivent exactement la même réponse 404 (`{"status":404,"error":"Page introuvable","fields":{}}`), sans trace ni détail.

**Abus** : limitation par adresse IP (`statuspage.public.rate-limit-per-minute`, 60 par défaut), puis 429 avec `Retry-After` ; la clé est l'adresse TCP, X-Forwarded-For n'est lu que derrière le reverse proxy de confiance avec `server.forward-headers-strategy=native` (ADR-0011), donc un client direct ne choisit pas sa clé. `Cache-Control: public, max-age=30` permet au proxy et aux navigateurs de servir les visites suivantes. Réponse bornée en taille (voir « Cache et charge »).

**XSS** : texte brut refusant `<` et `>` à l'écriture, rendu par React sans `dangerouslySetInnerHTML`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'` sur l'API.

**Session** : aucun cookie posé ni lu ; la page `/statut/:slug` est rendue hors du fournisseur d'authentification et lit l'API par `fetch` avec `credentials: "omit"`. CORS identique au reste de l'API. Lecture seule : POST, PUT, DELETE et PATCH répondent 401 sans jeton et 405 avec. Un test parcourt **toutes** les opérations du contrat OpenAPI réel et vérifie qu'elles exigent une authentification, hormis la connexion et cette lecture.

**Ce qu'un attaquant apprend** : quand un service affiché est en panne et l'historique de disponibilité sur 90 jours, donc les créneaux de maintenance et la fragilité d'un service (voir risque R15). Il ne peut ni deviner les autres pages, ni obtenir d'information sur le réseau interne, ni écrire.

## Incident automatique (désactivé par défaut)

`statuspage.auto-incident.enabled=true` : un composant en panne majeure depuis plus de `statuspage.auto-incident.minutes` (5) ouvre « Perturbation détectée » (en cours d'analyse, impact majeur, composant lié) ; il est résolu quand le composant se rétablit (état mesuré autre que la panne majeure, ou maintenance), avec une mise à jour. Un état inconnu ne démarre ni ne termine une panne. Idempotent : un indicateur par composant et un index unique partiel (un seul incident automatique ouvert par composant) ; un incident supprimé ou résolu par un administrateur pendant la panne n'est pas rouvert. Tâche sous verrou `JobGuard` (plusieurs instances).

## Conséquences

- Une page lisible par les usagers, alimentée par la supervision existante, sans second outil.
- Le choix des noms affichés et des messages engage l'administrateur : la page publique les montre à tout le monde (manuel, « avant de rendre une page publique »).
- Limites : pas d'abonnement des usagers (e-mail, flux RSS), pas de domaine ni de thème propres à la page, pas d'historique au-delà de 90 jours ni d'incidents au-delà de 14 jours sur la page publique, disponibilité inconnue pour un composant sans sonde ; le cache de chaque instance est local (avec plusieurs instances, une page peut avoir jusqu'à 30 s d'écart entre elles, et la limitation est comptée par instance) ; la limitation par adresse pénalise une école derrière une seule adresse NAT (relever la limite ou s'appuyer sur le cache du proxy).
