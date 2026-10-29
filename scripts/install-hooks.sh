#!/bin/sh
# Active les hooks versionnés du dépôt (.githooks).
cd "$(dirname "$0")/.." && git config core.hooksPath .githooks
echo "Hooks activés : commit-msg (Conventional Commits) et pre-commit (lint, format)."
