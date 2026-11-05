<#
.SYNOPSIS
    Construit le paquet Windows de l'agent : agent\dist\trans-dic1-agent-windows-<version>.zip et son .sha256.

.DESCRIPTION
    Le paquet contient le code de l'agent, install.ps1, uninstall.ps1, common.ps1, le modèle de
    configuration Windows, le README et MANIFEST.sha256 (empreinte de chaque fichier, contrôlée par
    install.ps1). Il ne contient ni test ni secret : le jeton est fourni à l'installation.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File agent\packaging\windows\build-package.ps1
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here 'common.ps1')
$agentDir = (Resolve-Path (Join-Path $here '..\..')).ProviderPath
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $agentDir 'dist' }

$initText = [System.IO.File]::ReadAllText((Join-Path $agentDir 'trans_dic1_agent\__init__.py'))
if ($initText -notmatch '(?m)^VERSION = "([^"]+)"') { throw 'version introuvable dans trans_dic1_agent/__init__.py' }
$version = $Matches[1]
$name = "trans-dic1-agent-windows-$version"

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("tdic1-build-" + [guid]::NewGuid().ToString('N'))
$stage = Join-Path $work $name
try {
    New-Item -ItemType Directory -Path $stage -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $agentDir 'trans_dic1_agent') -Destination $stage -Recurse
    Get-ChildItem -LiteralPath $stage -Recurse -Directory -Filter '__pycache__' | Remove-Item -Recurse -Force
    foreach ($file in @('install.ps1', 'uninstall.ps1', 'common.ps1', 'agent.conf.example')) {
        Copy-Item -LiteralPath (Join-Path $here $file) -Destination $stage
    }
    Copy-Item -LiteralPath (Join-Path $agentDir 'README.md') -Destination $stage

    $manifest = (Get-ManifestEntries $stage) -join "`n"
    [System.IO.File]::WriteAllText((Join-Path $stage 'MANIFEST.sha256'), $manifest + "`n", (New-Object System.Text.ASCIIEncoding))

    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $zip = Join-Path (Resolve-Path -LiteralPath $OutputDirectory).ProviderPath "$name.zip"
    if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $true)

    $hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText("$zip.sha256", "$hash  $name.zip`n", (New-Object System.Text.ASCIIEncoding))
    Write-Host "paquet : $zip"
    Write-Host "$hash  $name.zip"
}
finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
