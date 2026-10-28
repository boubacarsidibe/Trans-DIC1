# ADR-0002 — Construire Trans-DIC1 plutôt qu'adopter une solution de supervision existante

- Statut : proposée
- Date : 2026-10-28

## Contexte

Des outils open source couvrent déjà l'essentiel du besoin : Zabbix (collecte, alertes, tableaux de bord, passage à l'échelle par proxys), LibreNMS (découverte SNMP automatique, mise en route rapide) ou la combinaison Prometheus + `snmp_exporter` + Grafana pour la visualisation. Les comparatifs consultés ne justifient pas un développement sur mesure pour le seul besoin technique de supervision. Ils ne traitent pas non plus le cas d'un projet de fin d'études.

Cette question sera posée en soutenance et lors de la revue par le CRI. Elle doit avoir une réponse écrite.

## Options

| Option | Avantages | Limites |
| --- | --- | --- |
| A. Adopter Zabbix ou LibreNMS | Fonctionnel dès le premier jour, communauté, modèles d'équipements | Pas de valeur de conception pour le projet, personnalisation limitée du cycle de vie des alertes et des rôles EPT |
| B. Prometheus + Grafana | Visualisation très riche, stockage séries temporelles éprouvé | Assemblage de plusieurs outils, pas de gestion d'inventaire ni de cycle d'incident prise en charge/résolution |
| C. Construire Trans-DIC1 | Cycle d'alerte, rôles, audit et rapports adaptés au CRI, objet d'étude du projet | Coût de développement et de maintenance, couverture protocolaire plus étroite |
| D. Hybride : construire le métier, réutiliser des briques | Réutiliser une bibliothèque SNMP et PostgreSQL plutôt que réécrire les protocoles | Dépendances à choisir et à maintenir |

## Décision

Retenir **l'option D**. Trans-DIC1 reste une application sur mesure, car son objet est l'inventaire, le cycle de vie des alertes (`OUVERTE` → `PRISE_EN_CHARGE` → `RESOLUE` → `ARCHIVEE`), les rôles, l'audit et les rapports propres à l'EPT. Les protocoles et le stockage ne sont pas réécrits : bibliothèque SNMP existante, PostgreSQL, Spring Security.

## Périmètre explicitement exclu

Pas de découverte automatique du réseau, pas de bibliothèque de modèles par constructeur, pas de supervision de milliers d'équipements. Au-delà de ce périmètre, Zabbix ou LibreNMS redeviennent le bon choix.

## Conséquences

- La justification est écrite pour la soutenance.
- Le rapport doit comparer Trans-DIC1 à Zabbix et LibreNMS sur des critères mesurables : fonctions couvertes, effort de mise en route, limites.
- À vérifier avec le CRI : Zabbix ou LibreNMS sont-ils déjà en place ? Si oui, Trans-DIC1 doit pouvoir cohabiter, par exemple en important des mesures.
- Sources : [Zabbix vs LibreNMS](https://industrialmonitordirect.com/blogs/knowledgebase/zabbix-vs-librenms-network-monitoring-system-comparison), [Zabbix vs Grafana](https://last9.io/blog/zabbix-vs-grafana.md), [collecteurs SNMP auto-hébergés](https://www.pistack.xyz/posts/2026-04-26-snmp-collectors-snmp-exporter-snmpcollector-telegraf-self-hosted-guide-2026/).
