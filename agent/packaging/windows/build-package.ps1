#Requires -Version 5.1
<#
.SYNOPSIS
Construit le paquet Windows de l'agent : agent\dist\trans-dic1-agent-windows-<version>.zip et son empreinte.

.DESCRIPTION
Le paquet ne contient ni test ni secret : le jeton est fourni à l'installation.
Usage depuis la racine du dépôt : powershell -File agent\packaging\windows\build-package.ps1
#>
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$agentRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$init = Get-Content -LiteralPath (Join-Path $agentRoot 'trans_dic1_agent\__init__.py') -Raw
if ($init -notmatch 'VERSION = "([^"]+)"') { throw 'version introuvable dans trans_dic1_agent\__init__.py' }
$version = $Matches[1]

$name = "trans-dic1-agent-windows-$version"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("tdic1-build-" + [Guid]::NewGuid().ToString('N'))
$stage = Join-Path $work $name
$dist = Join-Path $agentRoot 'dist'
try {
    New-Item -ItemType Directory -Force -Path $stage, $dist | Out-Null
    Copy-Item -LiteralPath (Join-Path $agentRoot 'trans_dic1_agent') -Destination $stage -Recurse
    foreach ($file in 'install.ps1', 'uninstall.ps1', 'agent.conf.example') {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $stage
    }
    Get-ChildItem -LiteralPath $stage -Recurse -Directory -Filter '__pycache__' | Remove-Item -Recurse -Force

    $zip = Join-Path $dist "$name.zip"
    if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
    Compress-Archive -Path $stage -DestinationPath $zip -CompressionLevel Optimal

    $hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    # Même format que sha256sum : « empreinte  fichier ».
    [System.IO.File]::WriteAllText("$zip.sha256", "$hash  $name.zip`n", (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "paquet : agent\dist\$name.zip"
    Write-Host "$hash  $name.zip"
}
finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
