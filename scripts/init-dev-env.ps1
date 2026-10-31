[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path (Join-Path $PSScriptRoot '..') '.env'),
    [string]$ExampleFile = (Join-Path (Join-Path $PSScriptRoot '..') '.env.example')
)

# Prépare le fichier .env pour le développement local et pour docker compose :
# crée .env depuis .env.example s'il manque, puis génère uniquement les valeurs
# vides (mot de passe PostgreSQL, secret JWT, compte administrateur initial).
# Une valeur déjà renseignée n'est jamais modifiée. Aucun secret n'est affiché.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function New-RandomHex([int]$Bytes) {
    $buffer = New-Object byte[] $Bytes
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    return ([System.BitConverter]::ToString($buffer) -replace '-', '').ToLowerInvariant()
}

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    if (-not (Test-Path -LiteralPath $ExampleFile -PathType Leaf)) {
        throw "Modèle introuvable : $ExampleFile"
    }
    Copy-Item -LiteralPath $ExampleFile -Destination $EnvFile
    Write-Host "Fichier créé : $EnvFile" -ForegroundColor Green
}

$generated = @{
    DATABASE_PASSWORD         = { New-RandomHex 16 }
    JWT_SECRET                = { New-RandomHex 32 }
    BACKUP_ENCRYPTION_KEY     = { New-RandomHex 32 }
    BOOTSTRAP_ADMIN_USERNAME  = { 'admin' }
    BOOTSTRAP_ADMIN_EMAIL     = { 'admin@trans-dic1.local' }
    BOOTSTRAP_ADMIN_PASSWORD  = { 'Dev-' + (New-RandomHex 8) }
}

$lines = Get-Content -LiteralPath $EnvFile
$filled = New-Object System.Collections.Generic.List[string]
$result = foreach ($line in $lines) {
    if ($line -match '^\s*([A-Z0-9_]+)\s*=\s*$' -and $generated.ContainsKey($Matches[1])) {
        $name = $Matches[1]
        $filled.Add($name)
        "$name=$(& $generated[$name])"
    }
    else {
        $line
    }
}

if ($filled.Count -gt 0) {
    Set-Content -LiteralPath $EnvFile -Value $result -Encoding utf8
    Write-Host ("Valeurs générées : " + ($filled -join ', ')) -ForegroundColor Green
    Write-Host 'Les secrets sont dans le fichier .env (ignoré par git, ne jamais le commiter).'
}
else {
    Write-Host 'Aucune valeur vide à générer : .env est déjà complet.'
}
