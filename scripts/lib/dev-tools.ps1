# Fonctions communes à scripts/verify-all.ps1 et scripts/run-e2e-native.ps1 (chargées avec « . »).
# Syntaxe compatible Windows PowerShell 5.1 et PowerShell 7 : ni &&, ni opérateur ternaire, ni ??.
# Ce fichier ne lance rien par lui-même.

# Variables d'environnement modifiées pour les processus enfants : à remettre en état avec Restore-ProcessEnvironment.
$script:savedEnvironment = @{}

function Set-ProcessEnvironment([string]$Name, [string]$Value) {
    if (-not $script:savedEnvironment.ContainsKey($Name)) {
        $script:savedEnvironment[$Name] = [System.Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [System.Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Restore-ProcessEnvironment {
    foreach ($name in @($script:savedEnvironment.Keys)) {
        [System.Environment]::SetEnvironmentVariable($name, $script:savedEnvironment[$name], 'Process')
    }
    $script:savedEnvironment.Clear()
}

function Test-WindowsHost {
    return ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT)
}

function Get-HostExecutablePath {
    # powershell.exe ou pwsh.exe, selon le shell qui exécute le script.
    return (Get-Process -Id $PID).Path
}

function Get-ToolPath([string]$Name) {
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) { return $null }
    return $command.Source
}

function Remove-AnsiSequence([string]$Text) {
    return [regex]::Replace($Text, '\x1B\[[0-9;?]*[ -/]*[@-~]', '')
}

function Format-Duration([double]$Seconds) {
    $invariant = [System.Globalization.CultureInfo]::InvariantCulture
    if ($Seconds -lt 60) {
        return [string]::Format($invariant, '{0:0.0} s', $Seconds)
    }
    $total = [int][math]::Round($Seconds)
    $minutes = [int][math]::Floor($total / 60)
    $rest = $total - ($minutes * 60)
    return [string]::Format($invariant, '{0} min {1:00} s', $minutes, $rest)
}

# Version majeure d'un JDK lue dans le fichier « release » (sans lancer java, donc sans bruit sur stderr).
function Get-JdkMajorVersion([string]$JavaHome) {
    if ([string]::IsNullOrWhiteSpace($JavaHome)) { return 0 }
    if (-not (Test-Path -LiteralPath $JavaHome -PathType Container)) { return 0 }
    $release = Join-Path $JavaHome 'release'
    if (-not (Test-Path -LiteralPath $release -PathType Leaf)) { return 0 }
    foreach ($line in Get-Content -LiteralPath $release) {
        if ($line -match '^JAVA_VERSION="?(\d+)(\.(\d+))?') {
            if ($Matches[1] -eq '1') { return [int]$Matches[3] }
            return [int]$Matches[1]
        }
    }
    return 0
}

# JDK 21 : JAVA_HOME s'il en désigne un, sinon les installations usuelles de Windows, sinon $null.
function Find-Jdk21 {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $patterns = @(
        'C:\Program Files\Eclipse Adoptium\jdk-21*',
        'C:\Program Files\Java\jdk-21*',
        'C:\Program Files\Microsoft\jdk-21*'
    )
    foreach ($pattern in $patterns) {
        $directories = @(Get-ChildItem -Path $pattern -Directory -ErrorAction SilentlyContinue | Sort-Object -Property Name -Descending)
        foreach ($directory in $directories) { $candidates.Add($directory.FullName) }
    }
    foreach ($candidate in $candidates) {
        if ((Get-JdkMajorVersion $candidate) -eq 21) { return $candidate }
    }
    return $null
}

# Guillemets d'un argument de ligne de commande Windows (Start-Process joint les arguments sans les protéger).
function ConvertTo-QuotedArgument([string]$Value) {
    if ($Value.Length -eq 0) { return '""' }
    if ($Value -notmatch '[\s"]') { return $Value }
    $escaped = $Value -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

# Lance un programme, enregistre sa sortie standard et son erreur standard dans deux fichiers et
# attend sa fin (un signal de vie toutes les $HeartbeatSeconds secondes). N'utilise pas
# « Start-Process -Wait », qui attend aussi les processus descendants (un serveur lancé par le programme
# bloquerait le script), ni « 2>&1 », qui produit de fausses erreurs dans Windows PowerShell 5.1.
function Invoke-LoggedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$LogPrefix,
        [scriptblock]$OnHeartbeat,
        [int]$HeartbeatSeconds = 30
    )
    $outFile = $LogPrefix + '.out.log'
    $errFile = $LogPrefix + '.err.log'
    $startParameters = @{
        FilePath               = $FilePath
        WorkingDirectory       = $WorkingDirectory
        RedirectStandardOutput = $outFile
        RedirectStandardError  = $errFile
        NoNewWindow            = $true
        PassThru               = $true
    }
    if ($Arguments.Count -gt 0) {
        $quoted = @($Arguments | ForEach-Object { ConvertTo-QuotedArgument $_ })
        $startParameters['ArgumentList'] = ($quoted -join ' ')
    }
    $process = Start-Process @startParameters
    # Garder le descripteur du processus : sans cela, ExitCode peut rester vide sous Windows PowerShell 5.1.
    $null = $process.Handle
    while (-not $process.WaitForExit($HeartbeatSeconds * 1000)) {
        if ($null -ne $OnHeartbeat) { & $OnHeartbeat }
    }
    $process.WaitForExit()
    return [pscustomobject]@{
        ExitCode = [int]$process.ExitCode
        OutFile  = $outFile
        ErrFile  = $errFile
    }
}

# Lignes des fichiers de journal, sans séquences de couleur ni lignes vides en fin.
function Get-LogLines([string[]]$Files) {
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($file in $Files) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { continue }
        foreach ($line in [System.IO.File]::ReadAllLines($file)) {
            $lines.Add((Remove-AnsiSequence $line))
        }
    }
    return $lines.ToArray()
}

function Write-LogTail([string[]]$Files, [int]$Count = 40) {
    $lines = @(Get-LogLines $Files | Where-Object { $_.Trim().Length -gt 0 })
    $first = [math]::Max(0, $lines.Count - $Count)
    for ($index = $first; $index -lt $lines.Count; $index++) {
        Write-Host ('    ' + $lines[$index])
    }
}

function Test-TcpPortFree([int]$Port) {
    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    foreach ($listener in $listeners) {
        if ($listener.Port -eq $Port) { return $false }
    }
    return $true
}

function Test-HttpOk([string]$Url) {
    try {
        $request = [System.Net.HttpWebRequest]::Create($Url)
        $request.Proxy = $null
        $request.Timeout = 5000
        $response = $request.GetResponse()
        $ok = ([int]$response.StatusCode -eq 200)
        $response.Close()
        return $ok
    }
    catch {
        return $false
    }
}
