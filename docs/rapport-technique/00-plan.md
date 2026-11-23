# Rapport technique Trans-DIC1 — plan et fabrication

Rapport orienté fonctionnalités, choix justifiés, architecture, utilisation et traitement des incidents
(sans détail de code). Référence : branche `develop`, commit `0902d3b` (23 novembre 2026).

## Résultat

- `Trans-DIC1-Rapport-technique.pdf` : 59 pages A4 (garde, sommaire, 12 chapitres, annexes).

## Chapitres (`chapitres/`)

| Fichier | Contenu |
|---|---|
| `01-synthese.md` | Le projet en une page, état, chiffres, points forts, risques |
| `02-contexte-besoins.md` | Problème, objectifs, contraintes, acteurs, périmètre, cas d'utilisation |
| `03-demarche.md` | Sprints, modélisation, comparaison aux outils du marché, chronologie, règles de travail |
| `04-user-stories.md` | Chaque user story : comment elle est traitée et son état ; cas non couverts ; ajouts |
| `05-architecture.md` | Blocs, monolithe modulaire, chemin d'une mesure, événements, données, production |
| `06-choix-techniques.md` | Chaque choix : alternatives, raisons, contreparties ; choix qui ont évolué |
| `07-fonctionnalites.md` | Inventaire, collecte, alertes, notifications, astreinte, restitution, sécurité, administration |
| `08-utilisation.md` | Parcours par rôle, avec captures de l'application (`images/`) |
| `09-incidents.md` | Ce qui se passe et ce que fait l'équipe, situation par situation |
| `10-securite-qualite.md` | Protections, risques restants, tests, mise en production |
| `11-bilan.md` | Traité, partiel, non traité, limites, dette |
| `12-ameliorations.md` | Plan pour la dernière semaine, questions probables du jury, suite |
| `13-annexes.md` | Glossaire, démarrage, réglages, écrans, documents de référence |

Conventions : `{ok}`, `{partiel}`, `{non}` deviennent des badges ; deux images dans le même
paragraphe s'affichent côte à côte ; les diagrammes sont en Mermaid.

## Recompiler

Prérequis : Node.js 24, Python 3 avec `pypdf`, Google Chrome installé.

```text
cd docs/rapport-technique/build
npm install
node build.mjs
```

Le script rend les diagrammes dans Chrome, produit le PDF en deux passes (la seconde remplit les
numéros de page du sommaire) et fusionne la page de garde. Les fichiers intermédiaires vont dans
`build/out/` (ignoré par git).
