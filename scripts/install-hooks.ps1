# Active les hooks versionnés du dépôt (.githooks) : vérification des messages de commit et lint avant commit.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
git config core.hooksPath .githooks
Write-Host 'Hooks activés : commit-msg (Conventional Commits) et pre-commit (lint, format).'
