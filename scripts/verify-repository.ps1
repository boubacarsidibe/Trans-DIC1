[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot

try {
    $failures = [System.Collections.Generic.List[string]]::new()

    $requiredFiles = @(
        '.editorconfig',
        '.env.example',
        '.gitattributes',
        '.gitignore',
        'CHANGELOG.md',
        'CONTRIBUTING.md',
        'README.md',
        'SECURITY.md'
    )

    foreach ($requiredFile in $requiredFiles) {
        if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
            $failures.Add("Fichier requis absent : $requiredFile")
        }
    }

    $trackedFiles = @(git ls-files)
    if ($LASTEXITCODE -ne 0) {
        throw 'Impossible de lire les fichiers suivis par Git.'
    }

    $secretFilePatterns = @(
        '(^|/)\.env$',
        '(^|/)(id_rsa|id_ed25519)$',
        '(^|/)\.pgpass$',
        '\.(pem|key|p12|pfx|jks|keystore)$'
    )

    foreach ($trackedFile in $trackedFiles) {
        $normalizedPath = $trackedFile -replace '\\', '/'
        foreach ($pattern in $secretFilePatterns) {
            if ($normalizedPath -match $pattern) {
                $failures.Add("Fichier sensible suivi par Git : $trackedFile")
                break
            }
        }
    }

    if (Test-Path -LiteralPath '.env.example') {
        $sensitiveVariablePattern = '(?i)(PASSWORD|SECRET|TOKEN|COMMUNITY|PRIVATE_KEY|API_KEY)$'
        foreach ($line in Get-Content -LiteralPath '.env.example') {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
                $name = $Matches[1]
                $value = $Matches[2].Trim()
                if ($name -match $sensitiveVariablePattern -and $value.Length -gt 0) {
                    $failures.Add("La variable sensible $name doit rester vide dans .env.example.")
                }
            }
        }
    }

    if ($failures.Count -gt 0) {
        Write-Error ("Contrôle du dépôt échoué :`n- " + ($failures -join "`n- "))
        exit 1
    }

    Write-Host 'Contrôle du dépôt réussi.' -ForegroundColor Green
}
finally {
    Pop-Location
}

