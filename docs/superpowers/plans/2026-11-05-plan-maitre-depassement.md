# Plan maître : fiabiliser, combler les écarts, dépasser Zabbix

Date : 2026-11-05. Point de départ : branche `feature/sondes-sans-agent` (sondes sans agent faites, non fusionnée). Sources : [benchmark](../../benchmark-et-plan-depassement.md), [ADR-0019](../../adr/0019-sondes-actives.md).

## Règles de conduite

- **Ordre = priorité × faisabilité.** Priorité : ce qui rend l'acquis crédible, puis ce qu'un jury comparera d'abord à Zabbix, puis ce que les concurrents libres ne font pas. Faisabilité : réalisable et vérifiable ici, sans dépendance externe (CRI, Docker éteint, comptes tiers).
- **Linéaire pour tout ce qui touche le moteur d'alertes, l'ingestion, le schéma (numérotation Flyway) ou les mêmes écrans.** Un seul écrivain à la fois. **Parallèle seulement pour les relectures** (lecture seule).
- **Cycle de chaque point :** mini-spec dans le plan → tests d'abord → implémentation → relecture indépendante → une vague de corrections → relecture ciblée → commit. Rien n'est annoncé acquis sans test exécuté ; ce qui ne peut pas être exécuté ici est écrit comme tel.
- **Définition de fini :** tests verts (suite complète mesurée, pas annoncée), ADR ou section de doc mise à jour, entrée CHANGELOG, manuel si l'utilisateur voit la fonction.
- **Hors périmètre (dépend de l'extérieur) :** validation sur l'infrastructure EPT (#32), atelier de seuils avec le CRI (#33), SMS (compte d'opérateur), MFA propre (délégué à l'OIDC du CRI), proxys déportés et haute disponibilité (besoin de plusieurs machines). Ils restent documentés comme prochaines étapes.

## Phase 0 : fiabiliser l'acquis

| # | Point | Faisabilité | Dépend de |
| --- | --- | --- | --- |
| F0.1 | Défaut des deux sondes ping sur un même équipement (alerte qui clignote) | Haute | — |
| F0.2 | Fusion locale de la branche des sondes dans `develop` (sans pousser) | Haute | F0.1 |
| F0.3 | Contrôle local type intégration continue : un script qui lance serveur, interface, validation des fichiers Docker et dépôt | Haute | F0.2 |
| F0.4 | Essai Docker : démarrer le démon si possible, construire l'image, tester le ping non-root, lancer le test de bout en bout des sondes | Moyenne (dépend du poste) | F0.2 |

## Phase 1 : combler les écarts face à Zabbix

| # | Point (écart) | Faisabilité | Dépend de |
| --- | --- | --- | --- |
| F1.1 | Prévision de saturation : « disque plein dans N jours » (G6) | Haute (lit l'historique) | F0.2 |
| F1.2 | Absence de donnée et conditions composées ET/OU (G5) | Moyenne (touche le moteur) | F1.1 |
| F1.3 | Récepteurs de traps SNMP et de syslog (G4) | Moyenne | F0.2 |
| F1.4 | Bibliothèque de modèles d'équipement et application en un clic (G3) | Moyenne à grande | F0.2 |
| F1.5 | Agent Windows (G2) | Moyenne (`agent-windows.py` existe, non suivi par git) | F0.2 |
| F1.6 | Découverte des interfaces et disques par SNMP (G7) | Moyenne | F1.4 |
| F1.7 | Jetons d'API personnels et rapports de SLA planifiés par e-mail (G9) | Haute | F0.2 |

## Phase 2 : dépasser (ce que les concurrents libres ne font pas)

| # | Point | Faisabilité | Dépend de |
| --- | --- | --- | --- |
| F2.1 | Assistant de diagnostic d'incident : équipements touchés, cause racine probable, alertes voisines, changements récents | Haute (les données existent) | F0.2 |
| F2.2 | Astreinte : planning de garde, escalade vers la personne de garde | Moyenne | F0.2 |
| F2.3 | Page de statut des services de l'école, interne ou publique | Moyenne | F0.2 |
| F2.4 | Score de santé par site et par équipement | Haute | F1.1 |
| F2.5 | Réglage automatique des seuils avec simulation, étendu aux mesures de sonde (corrige D13) | Moyenne | F0.2 |
| F2.6 | Runbooks liés aux règles (procédure affichée dans l'alerte) | Haute | F0.2 |
| F2.7 | Rapport de capacité (croissance, échéance de saturation) | Haute | F1.1 |
| F2.8 | Application web installable (PWA) avec notifications | Moyenne | F2.2 |

## Phase 3 : livrables de soutenance

| # | Point | Dépend de |
| --- | --- | --- |
| F3.1 | Révision de l'ADR-0002 (périmètre) et nouveaux ADR | Fin de chaque point |
| F3.2 | Benchmark final avec ce qui est réalisé et vérifié | F2.x |
| F3.3 | Mise à jour du rapport final, du manuel et du plan de soutenance | F3.2 |
| F3.4 | Mesure de charge sur le poste (équipements, mesures par seconde) pour avoir un chiffre honnête | F1.x |

## Suivi

L'état de chaque point est tenu dans le journal `.superpowers/sdd/…/progress.md` de la branche et résumé dans `CHANGELOG.md`. Les points qui ne peuvent pas être terminés ici sont listés en fin de phase avec la raison.
