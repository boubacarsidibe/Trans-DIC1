<#
.SYNOPSIS
    Installe ou met à jour l'agent Trans-DIC1 sur un serveur Windows (Windows PowerShell 5.1).

.DESCRIPTION
    À lancer en administrateur, depuis le dossier décompressé du paquet
    trans-dic1-agent-windows-<version>.zip (vérifier d'abord son empreinte, voir docs/devops/agent.md).

    Étapes :
      1. contrôle du paquet (MANIFEST.sha256 : chaque fichier, ni manquant, ni modifié, ni ajouté) ;
      2. recherche de Python 3.8 ou plus récent, installé pour tous les utilisateurs ;
      3. code copié dans %ProgramFiles%\Trans-DIC1\agent (droits hérités : lecture pour tous,
         écriture pour les administrateurs) ;
      4. %ProgramData%\Trans-DIC1\agent créé avec une ACL restreinte (SYSTEM et Administrateurs
         seuls, héritage coupé) : configuration, jeton et journaux ;
      5. configuration créée une fois, jamais écrasée (seule server_url est mise à jour si donnée) ;
      6. jeton installé (jamais affiché), ACL restreinte sur le fichier ;
      7. vérification de la configuration (--check, sans envoi) ;
      8. tâche planifiée « Trans-DIC1 Agent » : au démarrage, sous SYSTEM, relancée toutes les
         5 minutes si elle s'est arrêtée (une seule instance à la fois).

    Mise à jour : relancer sans -TokenFile ni -ServerUrl ; configuration et jeton sont conservés.

.PARAMETER ServerUrl
    Adresse HTTPS de la plateforme (obligatoire à la première installation).
.PARAMETER TokenFile
    Fichier contenant le jeton d'agent (affiché une seule fois par la plateforme).
.PARAMETER PythonPath
    Chemin complet de python.exe, si la détection automatique ne convient pas.
.PARAMETER DryRun
    Affiche les actions sans rien modifier (les contrôles en lecture seule sont faits).
.PARAMETER NoStart
    Enregistre la tâche planifiée sans la démarrer.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\install.ps1 -ServerUrl https://supervision.example -TokenFile C:\Temp\jeton -DryRun
#>
[CmdletBinding()]
param(
    [string]$ServerUrl = '',
    [string]$TokenFile = '',
    [string]$PythonPath = '',
    [switch]$DryRun,
    [switch]$NoStart,
    [string]$ProgramFilesDir = $env:ProgramFiles,
    [string]$ProgramDataDir = $env:ProgramData
)

$ErrorActionPreference = 'Stop'
$packageDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $packageDir 'common.ps1')

function Write-Info([string]$Message) { Write-Host "trans-dic1-agent : $Message" }

function Stop-Install([string]$Message) {
    Write-Host "ERREUR : $Message" -ForegroundColor Red
    exit 1
}

function Invoke-Action([string]$Description, [scriptblock]$Action) {
    if ($DryRun) {
        Write-Host "[DRY-RUN] $Description"
        return
    }
    Write-Host "  - $Description"
    & $Action
}

function Invoke-Native([string]$FilePath, [string[]]$Arguments) {
    # Arguments passés tels quels (tableau) : pas d'interprétation par un shell.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $FilePath @Arguments 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ ExitCode = $code; Output = @($output | ForEach-Object { "$_" }) }
}

function Find-Python([string]$Requested) {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Requested) {
        $candidates.Add($Requested)
    }
    else {
        $launcher = Get-Command 'py.exe' -ErrorAction SilentlyContinue
        if ($launcher) {
            $run = Invoke-Native $launcher.Source @('-3', '-c', 'import sys; print(sys.executable)')
            if ($run.ExitCode -eq 0 -and $run.Output.Count -gt 0) { $candidates.Add($run.Output[0].Trim()) }
        }
        foreach ($command in @(Get-Command 'python.exe' -All -ErrorAction SilentlyContinue)) { $candidates.Add($command.Source) }
    }
    foreach ($candidate in $candidates) {
        if (-not (Test-PythonCandidate $candidate)) { continue }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $run = Invoke-Native $candidate @('-c', "import sys; print('%d.%d' % sys.version_info[:2])")
        if (($run.ExitCode -eq 0) -and ($run.Output.Count -gt 0) -and (Test-PythonVersionText $run.Output[0])) { return $candidate }
    }
    return $null
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding $false))
}

$layout = Get-AgentLayout -ProgramFiles $ProgramFilesDir -ProgramData $ProgramDataDir
if ($DryRun) { Write-Info 'mode -DryRun : aucune modification du système' }

# 0. Contrôles préalables (lecture seule).
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    if (-not $DryRun) { Stop-Install 'à lancer dans une console PowerShell ouverte en administrateur' }
    Write-Info 'avertissement : console non administrateur (accepté en -DryRun seulement)'
}
if (-not (Test-ServerUrl $ServerUrl)) { Stop-Install '-ServerUrl doit commencer par https://' }

# 1. Paquet intact.
$problems = @(Test-PackageManifest $packageDir)
if ($problems.Count -gt 0) {
    foreach ($problem in $problems) { Write-Host "  $problem" }
    Stop-Install 'le paquet ne correspond pas à son MANIFEST.sha256 : le retélécharger et vérifier son empreinte'
}
Write-Info 'paquet vérifié (MANIFEST.sha256)'

# 2. Python.
$python = Find-Python $PythonPath
if (-not $python) {
    Stop-Install ('Python 3.8 ou plus récent introuvable (installer Python « pour tous les utilisateurs » depuis python.org, ' +
        'ou donner -PythonPath ; le python.exe du Microsoft Store ne convient pas au compte SYSTEM)')
}
Write-Info "Python : $python"
if ($python -like "$env:SystemDrive\Users\*") {
    Write-Info 'avertissement : Python est installé dans un profil utilisateur ; préférer une installation pour tous les utilisateurs'
}

# 3. Jeton et configuration initiale.
$token = $null
if ($TokenFile) {
    if (-not (Test-Path -LiteralPath $TokenFile -PathType Leaf)) { Stop-Install "fichier de jeton introuvable : $TokenFile" }
    $token = ConvertTo-CleanToken ([System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenFile).ProviderPath))
    if ($token.Length -lt 20) { Stop-Install 'jeton invalide (trop court)' }
}
elseif (-not (Test-Path -LiteralPath $layout.TokenFile -PathType Leaf)) {
    if (-not $DryRun) { Stop-Install 'aucun jeton : utiliser -TokenFile' }
    Write-Info 'avertissement : aucun jeton installé ni fourni (-TokenFile obligatoire hors -DryRun)'
}
$configExists = Test-Path -LiteralPath $layout.ConfigFile -PathType Leaf
if ((-not $configExists) -and (-not $ServerUrl)) {
    if (-not $DryRun) { Stop-Install '-ServerUrl est obligatoire à la première installation' }
    Write-Info 'avertissement : -ServerUrl obligatoire à la première installation (hors -DryRun)'
}

# 4. Tâche existante arrêtée pendant la copie.
$existingTask = Get-ScheduledTask -TaskName $layout.TaskName -ErrorAction SilentlyContinue
if ($existingTask) {
    Invoke-Action "arrêt de la tâche planifiée « $($layout.TaskName) »" { Stop-ScheduledTask -TaskName $layout.TaskName }
}

# 5. Code.
$staging = $layout.AppDir + '.new'
Invoke-Action "copie du code dans $($layout.AppDir)" {
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $packageDir 'trans_dic1_agent') -Destination $staging -Recurse
    foreach ($name in @('README.md', 'agent.conf.example', 'common.ps1', 'uninstall.ps1')) {
        $source = Join-Path $packageDir $name
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $staging }
    }
    Get-ChildItem -LiteralPath $staging -Recurse -Directory -Filter '__pycache__' | Remove-Item -Recurse -Force
    if (Test-Path -LiteralPath $layout.AppDir) { Remove-Item -LiteralPath $layout.AppDir -Recurse -Force }
    Move-Item -LiteralPath $staging -Destination $layout.AppDir
}

# 6. Dossier de données : SYSTEM et Administrateurs seuls.
$dataAcl = Get-RestrictedAclArguments $layout.DataDir -Container
Invoke-Action "création de $($layout.DataDir) (icacls $($dataAcl -join ' '))" {
    New-Item -ItemType Directory -Path $layout.DataDir -Force | Out-Null
    $run = Invoke-Native 'icacls.exe' $dataAcl
    if ($run.ExitCode -ne 0) { throw "icacls a échoué (code $($run.ExitCode)) : $($run.Output -join ' ')" }
    New-Item -ItemType Directory -Path $layout.LogDir -Force | Out-Null
}

# 7. Configuration : créée une fois, jamais écrasée.
if (-not $configExists) {
    Invoke-Action "création de $($layout.ConfigFile)" {
        $template = [System.IO.File]::ReadAllText((Join-Path $layout.AppDir 'agent.conf.example'))
        Write-Utf8NoBom $layout.ConfigFile (ConvertTo-AgentConfigText -Template $template -ServerUrl $ServerUrl -TokenFile $layout.TokenFile)
    }
}
elseif ($ServerUrl) {
    Invoke-Action "server_url mise à jour dans $($layout.ConfigFile)" {
        $text = [System.IO.File]::ReadAllText($layout.ConfigFile)
        Write-Utf8NoBom $layout.ConfigFile (Set-ServerUrlInConfigText $text $ServerUrl)
    }
}

# 8. Jeton : jamais affiché, ACL restreinte explicite.
if ($null -ne $token) {
    $tokenAcl = Get-RestrictedAclArguments $layout.TokenFile
    Invoke-Action "installation du jeton dans $($layout.TokenFile) (icacls $($tokenAcl -join ' '))" {
        Write-Utf8NoBom $layout.TokenFile $token
        $run = Invoke-Native 'icacls.exe' $tokenAcl
        if ($run.ExitCode -ne 0) { throw "icacls a échoué (code $($run.ExitCode))" }
    }
}

# 9. Vérification sans envoi (sous le compte administrateur courant, autorisé par l'ACL).
Invoke-Action 'vérification de la configuration (--check, aucun envoi)' {
    Push-Location -LiteralPath $layout.AppDir
    try {
        $run = Invoke-Native $python @('-m', 'trans_dic1_agent', '--config', $layout.ConfigFile, '--check')
    }
    finally {
        Pop-Location
    }
    foreach ($line in $run.Output) { Write-Host "    $line" }
    if ($run.ExitCode -ne 0) { Stop-Install 'la vérification de la configuration a échoué' }
}

# 10. Tâche planifiée. Les objets sont construits même en -DryRun (aucun effet sans Register-ScheduledTask).
$systemAccount = (New-Object Security.Principal.SecurityIdentifier 'S-1-5-18').Translate([Security.Principal.NTAccount]).Value
$taskArguments = Get-AgentTaskArguments $layout.ConfigFile $layout.LogFile
$action = New-ScheduledTaskAction -Execute $python -Argument $taskArguments -WorkingDirectory $layout.AppDir
$atStartup = New-ScheduledTaskTrigger -AtStartup
# Relance de secours : si l'agent s'est arrêté (plantage), il repart dans les 5 minutes ;
# s'il tourne déjà, MultipleInstances IgnoreNew ignore le déclenchement.
$watchdog = New-ScheduledTaskTrigger -Once -At ((Get-Date).Date) -RepetitionInterval (New-TimeSpan -Minutes 5)
$taskPrincipal = New-ScheduledTaskPrincipal -UserId $systemAccount -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Invoke-Action ("enregistrement de la tâche « {0} » : {1} {2} (compte {3}, au démarrage + toutes les 5 min si arrêtée)" -f `
        $layout.TaskName, $python, $taskArguments, $systemAccount) {
    Register-ScheduledTask -TaskName $layout.TaskName -Action $action -Trigger @($atStartup, $watchdog) `
        -Principal $taskPrincipal -Settings $settings -Description 'Agent Trans-DIC1 : CPU, mémoire, disque, disponibilité' -Force | Out-Null
}
if (-not $NoStart) {
    Invoke-Action "démarrage de la tâche (journal : $($layout.LogFile))" { Start-ScheduledTask -TaskName $layout.TaskName }
}

if ($DryRun) {
    Write-Info 'fin du -DryRun : rien n''a été modifié'
}
else {
    Write-Info "installation terminée dans $($layout.AppDir)"
}
exit 0
