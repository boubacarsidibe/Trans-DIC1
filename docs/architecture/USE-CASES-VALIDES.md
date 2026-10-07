# Cas d'utilisation validés avec le porteur du projet

> Une version complétée par cohérence fonctionnelle est disponible dans [`USE-CASES-COMPLETS.md`](USE-CASES-COMPLETS.md). Ce document conserve uniquement les décisions explicitement validées pendant l'entretien.

Date de validation : 6 octobre 2026

## Acteurs retenus

Le diagramme métier principal contient uniquement trois acteurs humains.

- **Lecteur :** consulte la supervision de son périmètre sans effectuer de mutation.
- **Opérateur NOC :** possède les capacités du Lecteur et gère la supervision, l'inventaire, les alertes, la collecte, les agents, les maintenances et les rapports de son périmètre.
- **Administrateur :** possède les capacités du NOC et gère les utilisateurs, rôles, périmètres, notifications et planifications globales.

```text
Lecteur <|-- Opérateur NOC <|-- Administrateur
```

Les équipements, SNMP, l'agent Trans-DIC1, SMTP et le planificateur apparaîtront dans les diagrammes techniques, pas comme acteurs du diagramme métier principal.

## Cas communs hérités du Lecteur

### S'authentifier

Accéder au système avec un compte créé par l'Administrateur. Il n'existe pas d'inscription publique.

### Gérer son mot de passe

Chaque utilisateur peut modifier son mot de passe. « Mot de passe oublié » envoie un lien email temporaire à usage unique et révoque les anciennes sessions après changement.

### Consulter le tableau de bord

Afficher automatiquement les sites, équipements, dernières métriques et alertes du périmètre autorisé. L'actualisation est automatique en temps réel.

### Consulter les métriques

Filtrer les mesures par site, équipement, type et période, dans la limite du périmètre accordé.

### Consulter les alertes

Lire les alertes `INFO`, `AVERTISSEMENT` et `CRITIQUE`, leur état et leur historique, sans les modifier.

## Cas du NOC, également accessibles à l'Administrateur

### Gérer les équipements

Ajouter, consulter et modifier les équipements du périmètre. Deux retraits existent :

- **Archiver :** arrêter la supervision en conservant l'historique.
- **Supprimer définitivement :** supprimer l'équipement, ses métriques, alertes, notifications, services et configurations après confirmation explicite.

### Configurer la collecte SNMP

Configurer version, OID, fréquence et paramètres sécurisés. SNMPv3 est privilégié et les secrets ne sont jamais affichés ou journalisés en clair.

### Gérer les agents Trans-DIC1

Installer manuellement avec un jeton temporaire ou déployer par SSH, enregistrer, configurer, vérifier, mettre à jour, désactiver, désinstaller et révoquer un agent. Telnet est exclu.

### Déclencher une collecte immédiate

Demander « Collecter maintenant » en cas de besoin, sans attendre la prochaine collecte planifiée.

### Configurer les règles d'alerte

Créer, modifier, activer, désactiver ou supprimer une règle en choisissant métrique, seuil, unité, durée, sévérité initiale et équipements concernés.

### Prendre ou libérer une alerte

Un NOC peut se joindre aux responsables d'une alerte puis se retirer. Plusieurs NOC de même niveau peuvent être affectés simultanément, sans responsable principal.

### Acquitter une alerte

Indiquer qu'une alerte ouverte est prise en charge. Le commentaire est facultatif ; auteur, date et transition sont obligatoirement tracés.

### Résoudre une alerte

Résoudre directement une alerte ouverte ou acquittée. Le système la résout également automatiquement après confirmation du retour à la normale et distingue la résolution manuelle de la résolution automatique.

### Modifier la sévérité

Modifier la sévérité initialement déterminée par la règle entre `INFO`, `AVERTISSEMENT` et `CRITIQUE`. Toute modification est auditée.

### Gérer les périodes de maintenance

Planifier une maintenance pour un équipement ou un site. La collecte peut continuer, mais les alertes et emails sont inhibés jusqu'à la reprise automatique.

### Produire les rapports

Générer à la demande des rapports journaliers, hebdomadaires, mensuels ou personnalisés. L'export de la première version est au format PDF.

### Consulter le journal d'audit

Le NOC consulte uniquement les événements associés aux sites et équipements de son périmètre.

## Cas réservés à l'Administrateur

### Gérer les utilisateurs et les rôles

Créer, modifier, activer ou désactiver les comptes, réinitialiser les mots de passe et attribuer les rôles.

### Gérer les périmètres d'accès

Affecter un utilisateur à un ou plusieurs sites et, si nécessaire, à des équipements individuels. Ces restrictions s'appliquent aux vues, alertes, rapports et audits.

### Configurer les notifications email

Configurer le serveur email, les destinataires et les niveaux de sévérité concernés. La première version prend uniquement en charge l'email.

### Planifier les rapports automatiques

Choisir le type, la fréquence, l'heure, le périmètre et les destinataires email d'un rapport PDF automatique.

### Assigner les alertes

Affecter une alerte à plusieurs NOC autorisés, tous au même niveau de responsabilité.

### Consulter l'audit global

Consulter tous les événements, y compris les opérations globales sur les utilisateurs, rôles, périmètres et configurations.

## Diagrammes correspondants

- [`validated-global-use-cases.mmd`](diagrams/01-use-cases/validated-global-use-cases.mmd) : vue globale validée.
- [`monitoring-use-cases.mmd`](diagrams/01-use-cases/monitoring-use-cases.mmd) : supervision et incidents.
- [`administration-use-cases.mmd`](diagrams/01-use-cases/administration-use-cases.mmd) : administration globale.

## Décisions encore ouvertes

- comportement exact des alertes juste après une maintenance ;
- contenu détaillé des rapports PDF ;
- protocole sécurisé d'enrôlement de l'agent ;
- rétention du journal d'audit après suppression définitive ;
- délai de confirmation du retour à la normale.
