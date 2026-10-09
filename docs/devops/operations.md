# Exploitation, observabilité et continuité

## Baseline MVP des environnements

| Environnement | Exécution | Données | Secrets | Déploiement |
| --- | --- | --- | --- | --- |
| Development | Java, Node et PostgreSQL 16 locaux | fictives RFC 5737 | `.env` non versionné | manuel documenté |
| Testing | CI GitHub, H2 isolé | synthétiques | secrets de test sans valeur réelle | à chaque push/PR |
| Staging | hôte EPT à confirmer | assainies | secrets d'environnement | artefact immuable |
| Production | hôte EPT à confirmer | réelles autorisées | coffre EPT | approbation et rollback obligatoires |

Les emplacements exacts de staging et production restent une dépendance externe du CRI ; aucun fournisseur ni réseau n'est inventé.

## Objectifs initiaux

| Indicateur | Objectif MVP |
| --- | --- |
| Disponibilité mensuelle API | 99,5 % hors maintenance planifiée |
| Latence API lecture p95 | moins de 500 ms sur le réseau interne |
| Fraîcheur des métriques | moins de deux intervalles de collecte |
| Délai de notification | moins de 5 minutes après ouverture d'alerte |
| RPO PostgreSQL | 24 heures |
| RTO plateforme | 4 heures |

Ces valeurs sont une baseline d'ingénierie. Le CRI doit les confirmer avant le passage en production ; cette validation est suivie au Sprint 4.

## Santé

Chaque service devra fournir une vérification de vivacité et, s'il dépend de ressources externes, une vérification de disponibilité. Un échec de dépendance ne doit pas provoquer une boucle de redémarrage destructive.

## Logs

Format structuré recommandé : timestamp UTC, niveau, service, environnement, message, correlation ID et contexte non sensible. Ne jamais journaliser password, communauté SNMP, JWT complet, token, cookie, clé privée ou payload confidentiel.

## Métriques minimales

- disponibilité, débit, latence et erreurs HTTP de l'API ;
- CPU, RAM, disque et connexions DB ;
- durée et erreurs des collectes ;
- équipements injoignables ;
- alertes créées, acquittées et résolues ;
- notifications échouées ;
- profondeur et âge de file si une queue existe.

Les seuils doivent dériver de SLO explicites et éviter les alertes sans action possible.

## Sauvegardes

Après choix du stockage, définir :

- RPO et RTO approuvés ;
- fréquence et rétention ;
- chiffrement et stockage séparé ;
- droits de restauration minimaux ;
- test de restauration planifié et tracé.

Une sauvegarde non restaurée en test n'est pas considérée fiable.

## Migrations

Versionner les migrations, les tester en staging sur un volume représentatif et séparer les changements destructifs en plusieurs étapes compatibles. Sauvegarder avant toute migration irréversible.

## Rollback

Chaque release doit référencer l'artefact précédent. En cas d'échec de démarrage, healthcheck, migration ou régression critique :

1. arrêter la progression du déploiement ;
2. remettre l'artefact précédent ;
3. restaurer les données uniquement selon une procédure validée ;
4. vérifier santé et métriques ;
5. ouvrir un incident et conserver les éléments non sensibles utiles.
