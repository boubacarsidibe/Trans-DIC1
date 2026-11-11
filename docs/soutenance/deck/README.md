# Support de soutenance (Vigie, projet Trans-DIC1)

Le PowerPoint `deliverables/Trans-DIC1-soutenance.pptx` et le script `docs/soutenance/script-orateur.md`
sont générés : ne pas les modifier à la main, modifier les sources ci-dessous puis reconstruire.

| Fichier | Rôle |
|---|---|
| `build-deck.mjs` | Les 19 diapositives principales (dont une vidéo de secours masquée), les annexes, les animations et transitions |
| `notes.mjs` | Notes orateur minutées (15 min, deux voix) ; le script orateur en est tiré |
| `assets.mjs` | Fonds à pistes de circuit, logo, icônes, cadres de navigateur des captures |
| `render-diagrams.mjs` | Diagrammes Mermaid du projet rendus en clair pour les annexes |
| `demo/run-backend-demo.ps1` | Backend de démonstration : schéma `run_soutenance`, collecte simulée coupée, silence d'agent en 60 s |
| `demo/seed-demo.mjs` | Campus fictif (adresses TEST-NET), agents, 7 jours d'historique, panne en cascade, prise en charge |
| `demo/capture.mjs` | Captures d'écran et vidéo de la démonstration (Playwright, conversion MP4 par ffmpeg) |
| `export-slides.ps1` | Export PNG de chaque diapositive par PowerPoint, pour la relecture |

## Reconstruire

1. Lancer le backend de démonstration (configuration `backend-soutenance` de `.claude/launch.json`, ou
   `powershell -File docs/soutenance/deck/demo/run-backend-demo.ps1`) et l'interface (`npm --prefix frontend run dev`).
2. `node docs/soutenance/deck/demo/seed-demo.mjs <etat.json>` : laisser tourner (il garde les agents en vie).
   Les comptes créés doivent changer leur mot de passe à la première connexion ; pour la démonstration,
   lever cette obligation dans le schéma `run_soutenance` (`password_change_required = false`).
   Avec `--panne-auto`, la panne en cascade démarre seule et l'alerte racine est prise en charge (pour les captures).
   Sans cette option (démonstration en direct), la panne se pilote par un fichier à côté du fichier d'état :
   `New-Item <dossier>\panne.flag` coupe le routeur de cœur et la branche A (alertes en 1 à 2 min, mesuré 92 s),
   `Remove-Item <dossier>\panne.flag` les rétablit (résolution automatique, mesuré 13 s).
3. Après la prise en charge de l'alerte racine (≈ 3 min) : `node docs/soutenance/deck/demo/capture.mjs <etat.json> tout`.
4. `node docs/soutenance/deck/render-diagrams.mjs <dossier contenant node_modules/mermaid>` (annexes).
5. `node docs/soutenance/deck/build-deck.mjs`, puis contrôle visuel avec `export-slides.ps1`.

Les chiffres affichés (tests, ADR, migrations, issues) viennent de `build/stats.json` : les remesurer avant le jour J.
