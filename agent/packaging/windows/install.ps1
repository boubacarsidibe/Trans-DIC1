#Requires -Version 5.1
<#
.SYNOPSIS
Installe l'agent Trans-DIC1 sur un serveur Windows.

.DESCRIPTION
Copie l'agent, écrit sa configuration, protège le fichier du jeton par ACL (équivalent du « 600 »
de Linux), vérifie la configuration, puis enregistre une tâche planifiée qui lance l'agent au
démarrage de Windows et le relance en cas d'arrêt. L'agent s'exécute sous le compte limité
« LOCAL SERVICE » (ou SYSTEM avec -RunAsSystem). Le jeton n'est jamais affiché.

Première installation : -ServerUrl et -TokenFile sont obligatoires.
Mise à jour : relancer sans paramètre, la configuration et le jeton sont conservés.
Nouveau jeton : relancer avec -TokenFile.

.EXAMPLE
.\install.ps1 -ServerUrl https://supervision.ept.sn -TokenFile C:\Temp\jeton-agent.txt

.EXAMPLE
.\install.ps1 -WhatIf     # montre ce que ferait l'installation sans enregistrer la tâche
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$ServerUrl,
    [string]$TokenFile,
    [int]$Interval = 60,
    [string]$DiskPath,
    [string]$CaFile,
    [string]$Python,
    [string]$InstallDir,
    [string]$DataDir,
    [string]$TaskName = 'Trans-DIC1 Agent',
    [switch]$RunAsSystem,
    [switch]$NoTask,
    [switch]$AllowInsecureHttp
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Valeurs par défaut calculées ici : $PSScriptRoot est vide dans le bloc param() sous PowerShell 5.1.
if (-not $InstallDir) { $InstallDir = Join-Path $env:ProgramFiles 'Trans-DIC1-Agent' }
if (-not $DataDir) { $DataDir = Join-Path $env:ProgramData 'Trans-DIC1-Agent' }

# SID bien connus : indépendants de la langue de Windows.
$sidSystem = '*S-1-5-18'
$sidAdministrators = '*S-1-5-32-544'
$sidLocalService = '*S-1-5-19'

function Fail([string]$message) {
    Write-Host "ERREUR : $message" -ForegroundColor Red
    exit 1
}

function Step([string]$message) {
    Write-Host "- $message"
}

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    return ([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Write-TextNoBom([string]$path, [string]$text) {
    # configparser lit en UTF-8 strict : un BOM (ajouté par Set-Content -Encoding UTF8) casserait la 1re ligne.
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Find-Python {
    $candidates = @()
    if ($Python) {
        $candidates += $Python
    }
    else {
        $launcher = Get-Command py -ErrorAction SilentlyContinue
        if ($launcher) {
            $path = & $launcher.Source -3 -c 'import sys; print(sys.executable)' 2>$null
            # Un Python du Microsoft Store (WindowsApps) ne s'exécute que pour l'utilisateur qui l'a installé.
            if ($LASTEXITCODE -eq 0 -and $path -and $path -notlike '*\WindowsApps\*') { $candidates += $path.Trim() }
        }
        foreach ($command in @(Get-Command python -All -ErrorAction SilentlyContinue)) {
            # Le « python.exe » de WindowsApps n'est qu'un raccourci vers le Microsoft Store.
            if ($command.Source -notlike '*\WindowsApps\*') { $candidates += $command.Source }
        }
    }
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        & $candidate -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' 2>$null
        if ($LASTEXITCODE -eq 0) { return $candidate }
    }
    return $null
}

function Grant([string]$path, [string[]]$grants) {
    # Coupe l'héritage puis n'accorde que les droits listés.
    $arguments = @($path, '/inheritance:r', '/grant:r') + $grants
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # stderr d'un programme natif ne doit pas lever d'exception (PS 5.1)
    try { $output = & icacls @arguments 2>&1 } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { Fail "icacls a échoué sur $path : $output" }
}

# --- Vérifications préalables ------------------------------------------------------------------
$source = Join-Path $PSScriptRoot 'trans_dic1_agent'
if (-not (Test-Path -LiteralPath (Join-Path $source '__main__.py'))) {
    Fail "dossier trans_dic1_agent introuvable à côté du script ($PSScriptRoot) : lancer install.ps1 depuis le paquet décompressé."
}
if (-not $NoTask -and -not $WhatIfPreference -and -not (Test-Administrator)) {
    Fail 'droits administrateur requis pour enregistrer la tâche : ouvrir PowerShell en tant qu''administrateur.'
}

$pythonPath = Find-Python
if (-not $pythonPath) {
    Fail 'Python 3.8 ou plus récent introuvable. Installer Python pour tous les utilisateurs (python.org) ou indiquer -Python <chemin>.'
}
if (-not $RunAsSystem -and -not $NoTask -and $pythonPath -like (Join-Path (Split-Path $env:USERPROFILE -Parent) '*')) {
    Fail ("Python est installé dans un profil utilisateur ($pythonPath) : le compte LOCAL SERVICE ne peut pas le lire. " +
        'Installer Python pour tous les utilisateurs, ou relancer avec -RunAsSystem.')
}

$configFile = Join-Path $DataDir 'agent.conf'
$tokenTarget = Join-Path $DataDir 'token'
$logDir = Join-Path $DataDir 'logs'
$logFile = Join-Path $logDir 'agent.log'
$firstInstall = -not (Test-Path -LiteralPath $configFile)

if ($firstInstall -or $ServerUrl) {
    if (-not $ServerUrl) { Fail '-ServerUrl est obligatoire à la première installation.' }
    if ($ServerUrl -notmatch '^https://' -and -not ($AllowInsecureHttp -and $ServerUrl -match '^http://')) {
        Fail '-ServerUrl doit commencer par https:// : le jeton circulerait en clair.'
    }
    if ($Interval -lt 10 -or $Interval -gt 240) { Fail '-Interval doit être compris entre 10 et 240 secondes.' }
}
if ($firstInstall -or $TokenFile) {
    if (-not $TokenFile) { Fail '-TokenFile est obligatoire à la première installation.' }
    if (-not (Test-Path -LiteralPath $TokenFile -PathType Leaf)) { Fail "fichier de jeton introuvable : $TokenFile" }
}

if ($pythonPath -like '*\WindowsApps\*') {
    Fail 'ce Python vient du Microsoft Store et ne peut pas être lancé par une tâche système. Installer Python depuis python.org.'
}

$serviceSid = if ($RunAsSystem) { $sidSystem } else { $sidLocalService }
$serviceAccount = if ($RunAsSystem) { 'NT AUTHORITY\SYSTEM' } else { 'NT AUTHORITY\LOCAL SERVICE' }
# Sans tâche (essai manuel), le compte courant doit lui aussi pouvoir lire la configuration.
$extraGrant = @()
if ($NoTask) { $extraGrant = @("$($env:USERDOMAIN)\$($env:USERNAME)") }

if ($WhatIfPreference) {
    Write-Host "Simulation (-WhatIf) : aucun fichier ni tâche ne sera créé."
    Write-Host "- Python utilisé        : $pythonPath"
    Write-Host "- agent copié dans      : $InstallDir"
    Write-Host "- configuration et jeton: $DataDir"
    Write-Host "- tâche planifiée       : $TaskName (compte $serviceAccount, démarrage avec Windows, relance automatique)"
    exit 0
}

Write-Host "Installation de l'agent Trans-DIC1 ($pythonPath)"

# --- Fichiers ----------------------------------------------------------------------------------
Step "copie de l'agent dans $InstallDir"
New-Item -ItemType Directory -Force -Path $InstallDir, $DataDir, $logDir | Out-Null
$target = Join-Path $InstallDir 'trans_dic1_agent'
if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
Copy-Item -LiteralPath $source -Destination $InstallDir -Recurse
Get-ChildItem -LiteralPath $target -Recurse -Directory -Filter '__pycache__' | Remove-Item -Recurse -Force

if ($ServerUrl) {
    Step "écriture de la configuration $configFile"
    $lines = @(
        '# Configuration de l''agent Trans-DIC1. Voir agent.conf.example pour le détail des paramètres.',
        '[agent]',
        "server_url = $($ServerUrl.TrimEnd('/'))",
        "token_file = $tokenTarget",
        "interval = $Interval",
        "log_file = $logFile"
    )
    if ($DiskPath) { $lines += "disk_path = $DiskPath" }
    if ($CaFile) { $lines += "ca_file = $CaFile" }
    if ($AllowInsecureHttp) { $lines += 'allow_insecure_http = true' }
    Write-TextNoBom $configFile (($lines -join "`r`n") + "`r`n")
}

if ($TokenFile) {
    Step 'installation du jeton (jamais affiché)'
    $token = ([System.IO.File]::ReadAllText($TokenFile)).Trim()
    if ($token.Length -lt 20) { Fail 'le jeton lu est trop court : vérifier le fichier fourni.' }
    Write-TextNoBom $tokenTarget $token
    $token = $null
}

Step 'protection des fichiers (ACL)'
$readers = @("${sidSystem}:F", "${sidAdministrators}:F", "${serviceSid}:R")
foreach ($account in $extraGrant) { $readers += "${account}:F" }
Grant $tokenTarget $readers
Grant $configFile $readers
$logGrants = @("${sidSystem}:(OI)(CI)F", "${sidAdministrators}:(OI)(CI)F", "${serviceSid}:(OI)(CI)M")
foreach ($account in $extraGrant) { $logGrants += "${account}:(OI)(CI)F" }
Grant $logDir $logGrants

# --- Vérification de la configuration ----------------------------------------------------------
Step 'vérification de la configuration (aucun envoi)'
Push-Location $InstallDir
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $checkOutput = & $pythonPath -m trans_dic1_agent --config $configFile --check 2>&1
    $checkCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previous
    Pop-Location
}
if ($checkCode -ne 0) {
    $checkOutput | ForEach-Object { Write-Host "  $_" }
    Fail 'la configuration est invalide, voir ci-dessus. La tâche n''a pas été enregistrée.'
}
$checkOutput | ForEach-Object { Write-Host "  $_" }

if ($NoTask) {
    Write-Host ''
    Write-Host 'Installé sans tâche planifiée (-NoTask). Lancement manuel :'
    Write-Host "  cd `"$InstallDir`"; & `"$pythonPath`" -m trans_dic1_agent --config `"$configFile`""
    exit 0
}

# --- Tâche planifiée ---------------------------------------------------------------------------
Step "enregistrement de la tâche « $TaskName » (compte $serviceAccount)"
$arguments = '-m trans_dic1_agent --config "{0}"' -f $configFile
$action = New-ScheduledTaskAction -Execute $pythonPath -Argument $arguments -WorkingDirectory $InstallDir
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId $serviceAccount -LogonType ServiceAccount -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew

if ($PSCmdlet.ShouldProcess($TaskName, 'Enregistrer et démarrer la tâche planifiée')) {
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existing) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
        -Settings $settings -Description 'Agent de supervision Trans-DIC1 (envoie CPU, mémoire, disque et disponibilité).' | Out-Null
    Start-ScheduledTask -TaskName $TaskName
    Start-Sleep -Seconds 5
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    $state = (Get-ScheduledTask -TaskName $TaskName).State
    Write-Host "  état de la tâche : $state (dernier résultat : $($info.LastTaskResult))"
}

Write-Host ''
Write-Host 'Terminé. Supprimer maintenant le fichier de jeton d''origine si vous en avez un :'
if ($TokenFile) { Write-Host "  Remove-Item -LiteralPath `"$TokenFile`" -Force" }
Write-Host 'Vérifier le fonctionnement :'
Write-Host "  Get-Content `"$logFile`" -Tail 20 -Wait      # « 4 mesure(s) envoyée(s) » à chaque cycle"
Write-Host "  Get-ScheduledTask -TaskName `"$TaskName`" | Get-ScheduledTaskInfo"
