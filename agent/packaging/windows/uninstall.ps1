#Requires -Version 5.1
<#
.SYNOPSIS
Désinstalle l'agent Trans-DIC1 d'un serveur Windows.

.DESCRIPTION
Arrête et supprime la tâche planifiée, puis supprime les fichiers de l'agent. Sans -Purge, la
configuration, le jeton et les journaux sont conservés. Il faut ensuite révoquer l'agent dans la
plateforme.

.EXAMPLE
.\uninstall.ps1 -Purge
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$Purge,
    [string]$InstallDir,
    [string]$DataDir,
    [string]$TaskName = 'Trans-DIC1 Agent'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $InstallDir) { $InstallDir = Join-Path $env:ProgramFiles 'Trans-DIC1-Agent' }
if (-not $DataDir) { $DataDir = Join-Path $env:ProgramData 'Trans-DIC1-Agent' }

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdministrator = ([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue

if ($task -and -not $isAdministrator -and -not $WhatIfPreference) {
    Write-Host 'ERREUR : droits administrateur requis pour supprimer la tâche : ouvrir PowerShell en tant qu''administrateur.' -ForegroundColor Red
    exit 1
}

if ($task) {
    if ($PSCmdlet.ShouldProcess($TaskName, 'Arrêter et supprimer la tâche planifiée')) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "- tâche « $TaskName » supprimée"
    }
}
else {
    Write-Host "- aucune tâche « $TaskName » : rien à arrêter"
}

if (Test-Path -LiteralPath $InstallDir) {
    if ($PSCmdlet.ShouldProcess($InstallDir, 'Supprimer les fichiers de l''agent')) {
        Remove-Item -LiteralPath $InstallDir -Recurse -Force
        Write-Host "- $InstallDir supprimé"
    }
}

if ($Purge) {
    if (Test-Path -LiteralPath $DataDir) {
        if ($PSCmdlet.ShouldProcess($DataDir, 'Supprimer la configuration, le jeton et les journaux')) {
            Remove-Item -LiteralPath $DataDir -Recurse -Force
            Write-Host "- $DataDir supprimé (configuration, jeton et journaux)"
        }
    }
}
else {
    Write-Host "- configuration, jeton et journaux conservés dans $DataDir (-Purge pour les supprimer)"
}

Write-Host 'Terminé. Penser à révoquer l''agent dans la plateforme (page Collecte de l''équipement).'
