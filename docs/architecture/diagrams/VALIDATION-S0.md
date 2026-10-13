# Validation du jeu canonique — issue #5 (S0-ARCH)

Preuves de vérification des critères d'acceptation. Les sources vérifiées sont celles listées dans [`README.md`](README.md).

## Critères d'acceptation

| Critère | Résultat | Preuve |
| --- | --- | --- |
| Le catalogue désigne explicitement les diagrammes canoniques | Validé | Section « Jeu canonique validé » de `README.md` ; `00-overview.md` et `DIAGRAMS.md` portent un bandeau « antérieur / historique » |
| Le modèle persistant couvre les 10 entités sans héritage par type d'équipement | Validé | `02-domain/persistence-model-uml.mmd` : `Site`, `Equipement` (type par énumération `TypeEquipement`), `ServiceSupervise`, `Metrique`, `RegleAlerte`, `Alerte`, `Notification`, `Rapport`, `Utilisateur`, `EntreeAudit` ; aucune relation de généralisation |
| Les cas d'utilisation distinguent Administrateur, NOC, agent et service e-mail | Validé | `01-use-cases/trans-dic1-use-cases.mmd` : 2 acteurs humains, 2 systèmes externes, aucun équipement acteur |
| Le cycle d'alerte est cohérent entre les vues | Validé après correction | Voir ci-dessous |
| Diagrammes versionnés dans `docs/architecture` et relus par les deux membres | Partiel | Versionnés : oui. **Relecture croisée Boubacar / Khadija : à faire** |

## Cohérence du cycle d'alerte

États `OUVERTE`, `PRISE_EN_CHARGE`, `RESOLUE`, `ARCHIVEE`, `SUPPRIMEE` :

- États : `05-states/alert-lifecycle.mmd` (transitions complètes, réouverture avant archivage).
- Persistance : `Alerte.etat` plus les dates `priseEnChargeA`, `resolueA`, `archiveeA`, `supprimeeA`, `typeResolution` ; `EvenementAlerte` conserve l'historique ; `AffectationAlerte` porte l'affectation.
- Séquence de traitement : prise en charge (NOC), assignation (Administrateur), résolution.
- Séquence de collecte et activité : ouverture, mise à jour, résolution automatique si activée, alerte technique après échec définitif.
- Cas d'utilisation : prendre en charge, assigner, résoudre, archiver, supprimer.

### Incohérence corrigée

Une alerte technique d'échec de collecte (séquence et activité) n'a pas de règle, alors que le modèle imposait `RegleAlerte "1" -- "0..*" Alerte`. Correction dans `persistence-model-uml.mmd` : cardinalité `0..1` et attribut `Alerte.type` (`TypeAlerte` : `SEUIL` avec règle obligatoire, ou `COLLECTE` sans règle).

## Points à trancher à la relecture

1. **Rôle Lecteur.** `../USE-CASES-VALIDES.md` retient trois rôles humains (Lecteur, NOC, Administrateur). Le diagramme canonique n'en montre que deux. À confirmer : le Lecteur est-il supprimé ou simplement omis du diagramme ?
2. **Droits NOC sur archivage et suppression.** Le diagramme canonique les attribue au NOC ; à confirmer avec l'entretien métier.
3. **Syntaxe Mermaid.** Les fichiers n'ont pas été rendus automatiquement : vérifier l'affichage dans l'aperçu Mermaid ou StarUML.
