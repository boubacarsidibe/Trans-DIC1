# Exploitation, observabilité et continuité

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

