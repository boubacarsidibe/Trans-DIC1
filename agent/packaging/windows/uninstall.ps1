<#
.SYNOPSIS
    Désinstalle l'agent Trans-DIC1 de Windows (Windows PowerShell 5.1, en administrateur).

.DESCRIPTION
    Arrête et supprime la tâche planifiée « Trans-DIC1 Agent », puis supprime
    %ProgramFiles%\Trans-DIC1\agent. La configuration, le jeton et les journaux
    (%ProgramData%\Trans-DIC1\agent) sont conservés, sauf avec -Purge.
    Il faut ensuite révoquer l'agent dans la plateforme (fiche de l'équipement).

.PARAMETER Purge
    Supprime aussi la configuration, le jeton et les journaux.
.PARAMETER DryRun
    Affiche les actions sans rien modifier.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [switch]$Purge,
    [switch]$DryRun,
    [string]$ProgramFilesDir = $env:ProgramFiles,
    [string]$ProgramDataDir = $env:ProgramData
)

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'common.ps1')

function Invoke-Action([string]$Description, [scriptblock]$Action) {
    if ($DryRun) {
        Write-Host "[DRY-RUN] $Description"
        return
    }
    Write-Host "  - $Description"
    & $Action
}

$layout = Get-AgentLayout -ProgramFiles $ProgramFilesDir -ProgramData $ProgramDataDir
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if ((-not $DryRun) -and (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))) {
    Write-Host 'ERREUR : à lancer dans une console PowerShell ouverte en administrateur' -ForegroundColor Red
    exit 1
}

if (Get-ScheduledTask -TaskName $layout.TaskName -ErrorAction SilentlyContinue) {
    Invoke-Action "arrêt et suppression de la tâche « $($layout.TaskName) »" {
        Stop-ScheduledTask -TaskName $layout.TaskName
        Unregister-ScheduledTask -TaskName $layout.TaskName -Confirm:$false
    }
}
else {
    Write-Host "trans-dic1-agent : aucune tâche « $($layout.TaskName) » enregistrée"
}

# Le script peut être lancé depuis le dossier qu'il supprime : on en sort d'abord.
Invoke-Action "suppression de $($layout.AppDir)" {
    Set-Location -LiteralPath $env:TEMP
    if (Test-Path -LiteralPath $layout.AppDir) { Remove-Item -LiteralPath $layout.AppDir -Recurse -Force }
}

if ($Purge) {
    Invoke-Action "suppression de $($layout.DataDir) (configuration, jeton, journaux)" {
        if (Test-Path -LiteralPath $layout.DataDir) { Remove-Item -LiteralPath $layout.DataDir -Recurse -Force }
    }
    Write-Host 'trans-dic1-agent : désinstallé, configuration et jeton supprimés'
}
else {
    Write-Host "trans-dic1-agent : désinstallé (configuration conservée dans $($layout.DataDir))"
}
Write-Host 'Pensez à révoquer l''agent dans la plateforme (fiche de l''équipement).'
if ($DryRun) { Write-Host 'fin du -DryRun : rien n''a été modifié' }
exit 0
