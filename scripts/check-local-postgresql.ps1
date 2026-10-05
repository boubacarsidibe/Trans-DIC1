[CmdletBinding()]
param(
    [int]$Port = 5432,
    [string]$HostName = 'localhost'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$pathCommand = Get-Command pg_isready -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1
$candidatePaths = @(@(
    $pathCommand
    'C:\Program Files\PostgreSQL\16\bin\pg_isready.exe'
) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) })

if ($candidatePaths.Count -eq 0) {
    throw 'pg_isready introuvable. Installez PostgreSQL 16 ou ajoutez son dossier bin au PATH.'
}

$pgIsReady = $candidatePaths[0]
& $pgIsReady -h $HostName -p $Port
if ($LASTEXITCODE -ne 0) {
    throw "PostgreSQL ne répond pas sur ${HostName}:$Port."
}

Write-Host "PostgreSQL local répond sur ${HostName}:$Port." -ForegroundColor Green
