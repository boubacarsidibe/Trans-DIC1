# Modèle complet des cas d'utilisation de Trans-DIC1

Date : 6 octobre 2026

Cette version complète les décisions validées avec les fonctions logiquement nécessaires à leur fonctionnement. Les trois rôles suivent une généralisation : l'Administrateur possède les capacités du NOC, qui possède celles du Lecteur.

## Lecteur — consulter sans modifier le métier

### Accès et compte

- Se connecter.
- Réinitialiser un mot de passe oublié par lien email temporaire.
- Consulter et modifier ses informations personnelles non sensibles.
- Modifier son propre mot de passe.
- Se déconnecter.

### Consultation de la supervision

- Consulter le tableau de bord actualisé automatiquement.
- Consulter les sites et équipements autorisés par son périmètre.
- Consulter le détail et l'état d'un équipement.
- Analyser les métriques par type et période.
- Consulter les alertes et leur historique.
- Rechercher, filtrer, trier et paginer les informations consultables.

Le Lecteur ne génère pas de rapport et ne modifie ni équipement, ni alerte, ni configuration.

## Opérateur NOC — exploiter la supervision

Le NOC hérite de tous les cas du Lecteur.

### Inventaire

- Créer et modifier les sites de son périmètre.
- Ajouter et modifier les équipements.
- Archiver un équipement en conservant son historique.
- Restaurer un équipement archivé.
- Supprimer définitivement un équipement et toutes ses données associées après confirmation forte.
- Importer un inventaire en lot depuis un fichier contrôlé.
- Exporter l'inventaire de son périmètre.

L'import/export complète logiquement la gestion d'un inventaire susceptible de contenir de nombreux équipements. Il reste optionnel pour le premier MVP.

### Collecte SNMP et agent Trans-DIC1

- Configurer SNMPv3 et les OID à collecter.
- Tester la connectivité et la configuration avant activation.
- Installer manuellement un agent avec un jeton temporaire.
- Déployer un agent à distance par SSH.
- Enregistrer et associer l'agent à un serveur.
- Configurer les métriques et la fréquence de collecte.
- Consulter l'état, la version et la dernière communication de l'agent.
- Mettre à jour, désactiver, désinstaller ou révoquer un agent.
- Déclencher une collecte immédiate en cas de besoin.

Telnet reste exclu. Les secrets de collecte ne sont jamais révélés au client ou aux journaux.

### Alertes et incidents

- Créer, modifier, activer ou désactiver les règles d'alerte.
- Affecter les règles aux sites ou équipements.
- Consulter la chronologie complète d'un incident.
- Prendre une alerte ou se retirer de ses responsables.
- Acquitter une alerte avec commentaire facultatif.
- Résoudre directement une alerte ouverte ou acquittée.
- Modifier sa sévérité entre `INFO`, `AVERTISSEMENT` et `CRITIQUE`.
- Consulter les résolutions automatiques après retour à la normale.

Plusieurs NOC peuvent être affectés à la même alerte et ont le même niveau de responsabilité.

### Maintenance

- Planifier une maintenance pour un équipement ou un site.
- Modifier ou annuler une maintenance future.
- Terminer une maintenance avant l'heure prévue.
- Consulter l'historique des maintenances.

La collecte peut continuer pendant la maintenance ; la création d'alertes et les emails sont inhibés.

### Rapports, audit et exploitation

- Générer à la demande un rapport journalier, hebdomadaire, mensuel ou personnalisé.
- Exporter le rapport au format PDF.
- Consulter l'audit limité à ses sites et équipements.
- Consulter la santé de Trans-DIC1 : API, base de données, collecte et service email, sans exposer les détails sensibles.

## Administrateur — gouverner la plateforme

L'Administrateur hérite de tous les cas du NOC sans restriction de périmètre.

### Utilisateurs et autorisations

- Créer, modifier, activer et désactiver les utilisateurs.
- Attribuer `VIEWER`, `NOC_OPERATOR` ou `ADMIN`.
- Affecter les périmètres par site et par équipement.
- Réinitialiser le mot de passe d'un utilisateur.
- Révoquer ses sessions actives lors d'un incident de sécurité.

### Notifications

- Configurer et tester le service SMTP.
- Choisir les rôles ou destinataires notifiés.
- Choisir les sévérités déclenchant un email.
- Consulter les tentatives d'envoi.
- Relancer une notification échouée.

### Rapports automatiques

- Créer, modifier, activer ou désactiver une planification.
- Choisir période, fréquence, heure, sites, équipements et destinataires.
- Consulter l'historique des générations et échecs.

### Gouvernance et exploitation globale

- Assigner une alerte à plusieurs NOC autorisés.
- Consulter l'intégralité du journal d'audit.
- Configurer les paramètres globaux et fréquences par défaut.
- Configurer les durées de conservation des métriques, agrégats, alertes, rapports et audits dans les limites réglementaires.
- Sauvegarder et restaurer la configuration applicative. La restauration doit être confirmée et auditée.

## Fonctions automatiques du système

Ces comportements ne sont pas attribués à un acteur humain, mais sont nécessaires aux cas précédents :

- lancer les collectes planifiées ;
- normaliser et enregistrer les métriques ;
- détecter les dépassements de seuil sans doublon excessif ;
- ouvrir les alertes ;
- mettre à jour les écrans en temps réel ;
- envoyer les emails configurés ;
- résoudre automatiquement une alerte après retour confirmé à la normale ;
- terminer automatiquement une maintenance arrivée à échéance ;
- générer et envoyer les rapports planifiés ;
- appliquer la rétention et produire les traces d'audit.

## Priorisation recommandée

### P0 — indispensable au MVP

- authentification et rôles ;
- périmètres site/équipement ;
- consultation temps réel ;
- inventaire ;
- SNMP et agent ;
- règles, alertes, acquittement et résolution ;
- notifications email ;
- audit ;
- rapports PDF à la demande.

### P1 — très utile

- maintenance ;
- affectation de plusieurs NOC ;
- rapports automatiques ;
- état et mise à jour des agents ;
- test de configuration SNMP/SMTP ;
- santé de la plateforme.

### P2 — complémentaire

- import/export d'inventaire ;
- relance manuelle des emails ;
- sauvegarde/restauration de configuration ;
- configuration avancée des rétentions.

## Diagrammes

- [`complete-global-use-cases.mmd`](diagrams/01-use-cases/complete-global-use-cases.mmd)
- [`complete-reader-use-cases.mmd`](diagrams/01-use-cases/complete-reader-use-cases.mmd)
- [`complete-noc-use-cases.mmd`](diagrams/01-use-cases/complete-noc-use-cases.mmd)
- [`complete-admin-use-cases.mmd`](diagrams/01-use-cases/complete-admin-use-cases.mmd)
