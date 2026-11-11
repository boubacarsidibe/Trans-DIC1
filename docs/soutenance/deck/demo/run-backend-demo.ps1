# Backend de la démonstration de soutenance : schéma séparé (run_soutenance), collecte simulée coupée
# (chaque équipement a un vrai agent, alimenté par seed-demo.mjs), silence d'agent détecté en 60 s.
# Usage : powershell -ExecutionPolicy Bypass -File docs/soutenance/deck/demo/run-backend-demo.ps1
$env:COLLECTION_AGENT_SILENCE_SECONDS = '60'
# SPRING_APPLICATION_JSON l'emporte sur les variables d'environnement posées par run-backend-parallel.ps1.
$env:SPRING_APPLICATION_JSON = '{"collection":{"simulated":{"enabled":false}}}'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')
& (Join-Path $root 'scripts\run-backend-parallel.ps1') -Schema 'run_soutenance'
