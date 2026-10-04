# Politique de sécurité

## Versions supportées

Le projet n'a pas encore publié de version. Après la première release, la dernière version mineure stable sera supportée et la matrice sera tenue à jour ici.

| Version | Support |
| --- | --- |
| Pré-release | Développement uniquement |

## Signaler une vulnérabilité

Ne publiez pas une vulnérabilité exploitable dans une Issue publique. Contactez les mainteneurs par un canal privé approuvé par l'EPT ou utilisez le signalement privé GitHub lorsqu'il sera activé sur le repository.

Le signalement doit inclure, sans secret réel :

- le composant et la version concernés ;
- l'impact estimé ;
- les étapes minimales de reproduction ;
- une proposition de correction si disponible.

Les mainteneurs accuseront réception, qualifieront la gravité et coordonneront la correction avant toute divulgation.

## Données sensibles

Sont notamment sensibles : communautés et identifiants SNMP, adresses internes, topologie, credentials d'équipements, clés SSH, secrets JWT, tokens API, webhooks, identifiants SMTP et accès base de données.

Ces données ne doivent apparaître ni dans Git, ni dans les logs, images Docker, captures, rapports publics ou messages d'erreur. Utilisez des valeurs synthétiques dans les tests et la documentation.

## Bonnes pratiques attendues

- principe du moindre privilège ;
- rotation des secrets et révocation immédiate après exposition ;
- dépendances maintenues et verrouillées ;
- validation des entrées et contrôle d'accès côté serveur ;
- logs structurés avec masquage des données sensibles ;
- revue obligatoire des changements d'authentification, de réseau et de déploiement.

Un secret découvert dans l'historique doit être considéré compromis : retirez-le du service source, faites-le tourner, puis coordonnez le nettoyage de l'historique si nécessaire.

