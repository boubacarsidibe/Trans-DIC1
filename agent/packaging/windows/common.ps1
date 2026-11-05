# Fonctions pures partagées par install.ps1, uninstall.ps1 et build-package.ps1 (Windows PowerShell 5.1).
# Aucune ne modifie le système : elles calculent des chemins, des arguments et des contrôles.
# Testées par agent/tests/test_windows_packaging.py.

Set-StrictMode -Version 2.0

$script:TaskName = 'Trans-DIC1 Agent'
# SID bien connus (indépendants de la langue de Windows) : SYSTEM et Administrateurs.
$script:SidSystem = '*S-1-5-18'
$script:SidAdministrators = '*S-1-5-32-544'

function Get-AgentLayout {
    param(
        [Parameter(Mandatory = $true)][string]$ProgramFiles,
        [Parameter(Mandatory = $true)][string]$ProgramData
    )
    $appDir = Join-Path (Join-Path $ProgramFiles 'Trans-DIC1') 'agent'
    $dataDir = Join-Path (Join-Path $ProgramData 'Trans-DIC1') 'agent'
    return [pscustomobject]@{
        AppDir     = $appDir
        DataDir    = $dataDir
        ConfigFile = Join-Path $dataDir 'agent.conf'
        TokenFile  = Join-Path $dataDir 'token'
        LogDir     = Join-Path $dataDir 'logs'
        LogFile    = Join-Path (Join-Path $dataDir 'logs') 'agent.log'
        TaskName   = $script:TaskName
    }
}

function Test-ServerUrl([string]$Url) {
    # Vide : mise à jour sans changer l'adresse. Sinon HTTPS obligatoire, comme install.sh.
    if ([string]::IsNullOrEmpty($Url)) { return $true }
    return ($Url -match '^https://[^\s/]+')
}

function Test-PythonCandidate([string]$Path) {
    # Le raccourci python.exe du Microsoft Store (WindowsApps) n'est pas utilisable par SYSTEM.
    if ([string]::IsNullOrEmpty($Path)) { return $false }
    if (-not [System.IO.Path]::IsPathRooted($Path)) { return $false }
    if ($Path -match '\\WindowsApps\\') { return $false }
    return ($Path -match '\\python(w)?\.exe$')
}

function Test-PythonVersionText([string]$Text) {
    # Sortie attendue de « python -c "import sys; print('%d.%d' % sys.version_info[:2])" ».
    if ($Text -notmatch '^\s*(\d+)\.(\d+)\s*$') { return $false }
    $major = [int]$Matches[1]
    $minor = [int]$Matches[2]
    return (($major -gt 3) -or (($major -eq 3) -and ($minor -ge 8)))
}

function ConvertTo-AgentConfigText {
    # Modèle agent.conf.example -> agent.conf : adresse de la plateforme et chemins Windows.
    param(
        [Parameter(Mandatory = $true)][string]$Template,
        [Parameter(Mandatory = $true)][string]$ServerUrl,
        [Parameter(Mandatory = $true)][string]$TokenFile
    )
    $lines = $Template -split "`r?`n"
    $out = foreach ($line in $lines) {
        if ($line -match '^server_url\s*=') { "server_url = $ServerUrl" }
        elseif ($line -match '^token_file\s*=') { "token_file = $TokenFile" }
        else { $line }
    }
    return (($out -join "`r`n").TrimEnd() + "`r`n")
}

function Set-ServerUrlInConfigText([string]$Text, [string]$ServerUrl) {
    $lines = $Text -split "`r?`n"
    $out = foreach ($line in $lines) {
        if ($line -match '^server_url\s*=') { "server_url = $ServerUrl" } else { $line }
    }
    return (($out -join "`r`n").TrimEnd() + "`r`n")
}

function ConvertTo-CleanToken([string]$Text) {
    # Comme install.sh (tr -d ' \r\n') : espaces, retours chariot, BOM éventuel retirés.
    if ($null -eq $Text) { return '' }
    return ($Text -replace "[\s\uFEFF]", '')
}

function Get-RestrictedAclArguments([string]$Path, [switch]$Container) {
    # Arguments fixes d'icacls : héritage coupé, contrôle total pour SYSTEM et Administrateurs seuls.
    $grant = 'F'
    if ($Container) { $grant = '(OI)(CI)F' }
    return @($Path, '/inheritance:r', '/grant:r', "$($script:SidSystem):$grant", "$($script:SidAdministrators):$grant")
}

function Get-AgentTaskArguments([string]$ConfigFile, [string]$LogFile) {
    return ('-m trans_dic1_agent --config "{0}" --log-file "{1}"' -f $ConfigFile, $LogFile)
}

function Get-ManifestEntries([string]$Root) {
    # Une ligne « <sha256>  <chemin relatif> » par fichier, chemins triés, séparateur « / ».
    $rootFull = (Resolve-Path -LiteralPath $Root).ProviderPath.TrimEnd('\')
    $files = Get-ChildItem -LiteralPath $rootFull -Recurse -File |
        Where-Object { ($_.Name -ne 'MANIFEST.sha256') -and ($_.FullName -notmatch '\\__pycache__\\') }
    $entries = foreach ($file in $files) {
        $relative = $file.FullName.Substring($rootFull.Length + 1).Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
    return @($entries | Sort-Object { $_.Substring(66) })
}

function Test-PackageManifest([string]$Root) {
    # Renvoie la liste des écarts (vide si le dossier correspond exactement à MANIFEST.sha256).
    $manifest = Join-Path $Root 'MANIFEST.sha256'
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { return @('MANIFEST.sha256 absent') }
    $expected = @{}
    foreach ($line in (Get-Content -LiteralPath $manifest -Encoding ASCII)) {
        if ($line -match '^([0-9a-f]{64})  (.+)$') { $expected[$Matches[2]] = $Matches[1] }
        elseif ($line.Trim()) { return @("ligne illisible dans MANIFEST.sha256 : $line") }
    }
    $problems = New-Object System.Collections.Generic.List[string]
    $actual = @{}
    foreach ($entry in (Get-ManifestEntries $Root)) { $actual[$entry.Substring(66)] = $entry.Substring(0, 64) }
    foreach ($name in $expected.Keys) {
        if (-not $actual.ContainsKey($name)) { $problems.Add("fichier manquant : $name") }
        elseif ($actual[$name] -ne $expected[$name]) { $problems.Add("empreinte différente : $name") }
    }
    foreach ($name in $actual.Keys) {
        if (-not $expected.ContainsKey($name)) { $problems.Add("fichier inattendu : $name") }
    }
    return @($problems | Sort-Object)
}
