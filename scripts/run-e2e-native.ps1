<#
.SYNOPSIS
    Tests de bout en bout (Playwright) sur une pile native, sans Docker.

.DESCRIPTION
    Remplace scripts « e2e/run-e2e.sh » sur un poste de développement Windows. Tout tourne en natif :
      1. un cluster PostgreSQL 16 jetable, créé avec les outils du PostgreSQL 16 installé (initdb, pg_ctl) dans un
         dossier temporaire, sur le port 15432 de 127.0.0.1 : le service PostgreSQL du poste, ses bases et ses
         ports ne sont jamais touchés ;
      2. le backend (jar Spring Boot construit si absent ou plus ancien que les sources) sur le port 18080 ;
      3. l'interface (build de production servi par « vite preview ») sur le port 15173 ;
      4. « playwright test » avec Google Chrome (ou Edge) installé sur le poste.
    Secrets aléatoires à chaque exécution, base vide, tout est arrêté et supprimé à la fin (les journaux sont gardés
    en cas d'échec).

    Code de sortie : 0 réussite, 1 échec, 3 prérequis absents (l'appelant affiche alors SKIPPED, jamais PASSED).

.PARAMETER CheckPrerequisites
    Vérifie seulement les prérequis : affiche « OK » (code 0) ou « SKIPPED: raison » (code 3).
.PARAMETER SummaryFile
    Fichier où écrire la ligne de résultat (lue par verify-all.ps1).
.PARAMETER PgBin
    Dossier bin d'un PostgreSQL 16 (défaut : variable E2E_PG_BIN, puis C:\Program Files\PostgreSQL\16\bin).
.PARAMETER RebuildBackend
    Reconstruit le jar du backend même s'il paraît à jour.
.PARAMETER PlaywrightArguments
    Arguments transmis à « playwright test » (par exemple e2e/alert.spec.js).
#>
[CmdletBinding()]
param(
    [switch]$CheckPrerequisites,
    [string]$SummaryFile,
    [string]$PgBin,
    [int]$PostgresPort = 15432,
    [int]$ApiPort = 18080,
    [int]$WebPort = 15173,
    [switch]$RebuildBackend,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$PlaywrightArguments = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptDirectory = $PSScriptRoot
$repositoryRoot = Split-Path -Parent $scriptDirectory
. (Join-Path $scriptDirectory 'lib\dev-tools.ps1')

$frontendDirectory = Join-Path $repositoryRoot 'frontend'
$backendDirectory = Join-Path $repositoryRoot 'backend'

function New-RandomHex([int]$Bytes) {
    $buffer = New-Object byte[] $Bytes
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    return ([System.BitConverter]::ToString($buffer) -replace '-', '').ToLowerInvariant()
}

# Adresse IPv4 non locale du poste, joignable depuis le backend : la sonde TCP du test de sondes ne peut pas viser
# la boucle locale (refusée par la garde des cibles). Préfère une interface qui a une passerelle.
function Get-ProbeTargetAddress {
    $withGateway = New-Object System.Collections.Generic.List[string]
    $withoutGateway = New-Object System.Collections.Generic.List[string]
    $interfaces = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()
    foreach ($interface in $interfaces) {
        if ($interface.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) { continue }
        if ($interface.NetworkInterfaceType -eq [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback) { continue }
        if ($interface.NetworkInterfaceType -eq [System.Net.NetworkInformation.NetworkInterfaceType]::Tunnel) { continue }
        $properties = $interface.GetIPProperties()
        foreach ($unicast in $properties.UnicastAddresses) {
            if ($unicast.Address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { continue }
            $text = $unicast.Address.ToString()
            if ($text.StartsWith('127.') -or $text.StartsWith('169.254.') -or $text.StartsWith('0.')) { continue }
            if ($properties.GatewayAddresses.Count -gt 0) { $withGateway.Add($text) } else { $withoutGateway.Add($text) }
        }
    }
    if ($withGateway.Count -gt 0) { return $withGateway[0] }
    if ($withoutGateway.Count -gt 0) { return $withoutGateway[0] }
    return $null
}

function Find-PostgresBin {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($PgBin) { $candidates.Add($PgBin) }
    if ($env:E2E_PG_BIN) { $candidates.Add($env:E2E_PG_BIN) }
    $candidates.Add('C:\Program Files\PostgreSQL\16\bin')
    foreach ($candidate in $candidates) {
        $initdb = Join-Path $candidate 'initdb.exe'
        if (-not (Test-Path -LiteralPath $initdb -PathType Leaf)) { continue }
        if (-not (Test-Path -LiteralPath (Join-Path $candidate 'pg_ctl.exe') -PathType Leaf)) { continue }
        if (-not (Test-Path -LiteralPath (Join-Path $candidate 'createdb.exe') -PathType Leaf)) { continue }
        $version = [string](& $initdb --version)
        if ($version -match '\) 16\.') { return $candidate }
    }
    return $null
}

function Find-Browser {
    # Retourne le canal Playwright à utiliser ('' = celui de la configuration, déjà défini par l'utilisateur) ou $null.
    if ($env:E2E_BROWSER_CHANNEL) { return '' }
    $chrome = New-Object System.Collections.Generic.List[string]
    if ($env:ProgramFiles) { $chrome.Add((Join-Path $env:ProgramFiles 'Google\Chrome\Application\chrome.exe')) }
    if (${env:ProgramFiles(x86)}) { $chrome.Add((Join-Path ${env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe')) }
    if ($env:LOCALAPPDATA) { $chrome.Add((Join-Path $env:LOCALAPPDATA 'Google\Chrome\Application\chrome.exe')) }
    foreach ($path in $chrome) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { return '' }
    }
    if (${env:ProgramFiles(x86)}) {
        if (Test-Path -LiteralPath (Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe') -PathType Leaf) { return 'msedge' }
    }
    return $null
}

# Retourne $null si tout est prêt, sinon la raison pour laquelle les tests ne peuvent pas tourner ici.
function Get-MissingPrerequisite {
    if (-not (Test-WindowsHost)) { return 'script prévu pour Windows (PostgreSQL 16 natif et Chrome installés)' }
    if (-not (Find-PostgresBin)) {
        return 'PostgreSQL 16 introuvable : outils initdb, pg_ctl et createdb attendus dans C:\Program Files\PostgreSQL\16\bin (ou -PgBin, ou variable E2E_PG_BIN)'
    }
    if (-not (Find-Jdk21)) { return 'JDK 21 introuvable (JAVA_HOME ou C:\Program Files\Eclipse Adoptium\jdk-21*)' }
    if (-not (Get-ToolPath 'node.exe')) { return 'Node.js introuvable (Node 24 requis)' }
    if ($null -eq (Find-Browser)) {
        return 'ni Google Chrome ni Microsoft Edge installés (ou définir E2E_BROWSER_CHANNEL pour un navigateur installé de Playwright)'
    }
    foreach ($port in @($PostgresPort, $ApiPort, $WebPort)) {
        if (-not (Test-TcpPortFree $port)) { return "port $port déjà utilisé sur ce poste (ports de la pile de test : $PostgresPort, $ApiPort, $WebPort)" }
    }
    return $null
}

$missing = Get-MissingPrerequisite
if ($CheckPrerequisites) {
    if ($missing) {
        Write-Output "SKIPPED: $missing"
        exit 3
    }
    Write-Output 'OK'
    exit 0
}
if ($missing) {
    Write-Host "SKIPPED: $missing" -ForegroundColor Yellow
    exit 3
}

# ---------------------------------------------------------------------------------------------------
# Exécution
# ---------------------------------------------------------------------------------------------------

$postgresBin = Find-PostgresBin
$jdk = Find-Jdk21
$node = Get-ToolPath 'node.exe'
$java = Join-Path $jdk 'bin\java.exe'
$browserChannel = Find-Browser

$workDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('tdic1-e2e-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$logDirectory = Join-Path $workDirectory 'logs'
$dataDirectory = Join-Path $workDirectory 'pgdata'
$webDirectory = Join-Path $workDirectory 'web'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

$databasePassword = New-RandomHex 16
$jwtSecret = New-RandomHex 32
$e2ePassword = 'E2e-' + (New-RandomHex 12)

$postgresStarted = $false
$backendProcess = $null
$webProcess = $null
$exitCode = 1
$summary = ''
$apiUrl = "http://localhost:$ApiPort/api/v1"
$baseUrl = "http://localhost:$WebPort"

function Stop-Started($Process) {
    if ($null -eq $Process) { return }
    try {
        if (-not $Process.HasExited) {
            Stop-Process -Id $Process.Id -Force
            $null = $Process.WaitForExit(15000)
        }
    }
    catch {
        Write-Host "  Arrêt du processus $($Process.Id) impossible : $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

function Wait-Http([string]$Url, [int]$TimeoutSeconds, $Process, [string[]]$LogFiles) {
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if (Test-HttpOk $Url) { return }
        if ($Process.HasExited) {
            Write-LogTail $LogFiles 30
            throw "le processus s'est arrêté avant de répondre sur $Url (code $($Process.ExitCode))"
        }
        Start-Sleep -Seconds 2
    }
    Write-LogTail $LogFiles 30
    throw "aucune réponse de $Url après $TimeoutSeconds s"
}

try {
    Write-Host "Pile de test native (sans Docker) : PostgreSQL $PostgresPort, API $ApiPort, interface $WebPort" -ForegroundColor Cyan
    Write-Host "Dossier de travail : $workDirectory"
    Set-ProcessEnvironment 'JAVA_HOME' $jdk

    # --- 1. PostgreSQL 16 jetable --------------------------------------------------------------------
    Write-Host '1/4 PostgreSQL 16 jetable (cluster temporaire)'
    $passwordFile = Join-Path $workDirectory 'pg.pwd'
    [System.IO.File]::WriteAllText($passwordFile, $databasePassword)
    $run = Invoke-LoggedProcess -FilePath (Join-Path $postgresBin 'initdb.exe') `
        -Arguments @('-D', $dataDirectory, '-U', 'trans_dic1', '-A', 'scram-sha-256', "--pwfile=$passwordFile", '-E', 'UTF8', '--locale=C', '--no-sync') `
        -WorkingDirectory $workDirectory -LogPrefix (Join-Path $logDirectory 'initdb')
    Remove-Item -LiteralPath $passwordFile -Force
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile) 20
        throw "initdb a échoué (code $($run.ExitCode))"
    }
    $options = "-p $PostgresPort -c listen_addresses=127.0.0.1 -c fsync=off -c synchronous_commit=off -c full_page_writes=off"
    $run = Invoke-LoggedProcess -FilePath (Join-Path $postgresBin 'pg_ctl.exe') `
        -Arguments @('start', '-D', $dataDirectory, '-l', (Join-Path $logDirectory 'postgres.log'), '-w', '-t', '90', '-o', $options) `
        -WorkingDirectory $workDirectory -LogPrefix (Join-Path $logDirectory 'pg_ctl-start')
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile, (Join-Path $logDirectory 'postgres.log')) 20
        throw "pg_ctl start a échoué (code $($run.ExitCode))"
    }
    $postgresStarted = $true
    Set-ProcessEnvironment 'PGPASSWORD' $databasePassword
    $run = Invoke-LoggedProcess -FilePath (Join-Path $postgresBin 'createdb.exe') `
        -Arguments @('-h', '127.0.0.1', '-p', [string]$PostgresPort, '-U', 'trans_dic1', 'trans_dic1') `
        -WorkingDirectory $workDirectory -LogPrefix (Join-Path $logDirectory 'createdb')
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile) 20
        throw "createdb a échoué (code $($run.ExitCode))"
    }
    Write-Host '  base trans_dic1 vide, prête'

    # --- 2. Backend ----------------------------------------------------------------------------------
    Write-Host '2/4 Backend'
    $jar = Get-ChildItem -LiteralPath (Join-Path $backendDirectory 'target') -Filter '*.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTime -Descending | Select-Object -First 1
    $newestSource = Get-ChildItem -LiteralPath (Join-Path $backendDirectory 'src') -Recurse -File |
        Sort-Object -Property LastWriteTime -Descending | Select-Object -First 1
    $pomTime = (Get-Item -LiteralPath (Join-Path $backendDirectory 'pom.xml')).LastWriteTime
    $stale = $RebuildBackend -or ($null -eq $jar) -or ($newestSource.LastWriteTime -gt $jar.LastWriteTime) -or ($pomTime -gt $jar.LastWriteTime)
    if ($stale) {
        Write-Host '  construction du jar (mvnw -DskipTests package)'
        $run = Invoke-LoggedProcess -FilePath (Join-Path $backendDirectory 'mvnw.cmd') `
            -Arguments @('-B', '--no-transfer-progress', '-DskipTests', 'package') `
            -WorkingDirectory $backendDirectory -LogPrefix (Join-Path $logDirectory 'backend-package') `
            -OnHeartbeat { Write-Host '  ... construction en cours' }
        if ($run.ExitCode -ne 0) {
            Write-LogTail @($run.OutFile, $run.ErrFile) 30
            throw "la construction du backend a échoué (code $($run.ExitCode))"
        }
        $jar = Get-ChildItem -LiteralPath (Join-Path $backendDirectory 'target') -Filter '*.jar' -File |
            Sort-Object -Property LastWriteTime -Descending | Select-Object -First 1
    }
    else {
        Write-Host "  jar à jour : $($jar.Name)"
    }

    $probeAddress = Get-ProbeTargetAddress
    $backendEnvironment = [ordered]@{
        APP_PORT                  = [string]$ApiPort
        DATABASE_HOST             = '127.0.0.1'
        DATABASE_PORT             = [string]$PostgresPort
        DATABASE_NAME             = 'trans_dic1'
        DATABASE_USER             = 'trans_dic1'
        DATABASE_PASSWORD         = $databasePassword
        JWT_SECRET                = $jwtSecret
        BOOTSTRAP_ADMIN_USERNAME  = 'e2e-admin'
        BOOTSTRAP_ADMIN_EMAIL     = 'e2e-admin@example.test'
        BOOTSTRAP_ADMIN_PASSWORD  = $e2ePassword
        REFRESH_COOKIE_SECURE     = 'false'
        CORS_ALLOWED_ORIGINS      = $baseUrl
        PUBLIC_URL                = $baseUrl
        LOGIN_RATE_LIMIT_ATTEMPTS = '1000'
        SMTP_HOST                 = 'localhost'
    }
    foreach ($entry in $backendEnvironment.GetEnumerator()) { Set-ProcessEnvironment $entry.Key ([string]$entry.Value) }
    $backendOut = Join-Path $logDirectory 'backend.out.log'
    $backendErr = Join-Path $logDirectory 'backend.err.log'
    $backendArguments = (@('-XX:MaxRAMPercentage=40', '-jar', $jar.FullName) | ForEach-Object { ConvertTo-QuotedArgument $_ }) -join ' '
    $backendProcess = Start-Process -FilePath $java -ArgumentList $backendArguments `
        -WorkingDirectory $backendDirectory -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr -NoNewWindow -PassThru
    $null = $backendProcess.Handle
    Write-Host "  démarrage du backend (jar $($jar.Name)), attente de /actuator/health/readiness"
    Wait-Http "http://127.0.0.1:$ApiPort/actuator/health/readiness" 300 $backendProcess @($backendOut, $backendErr)
    Write-Host '  backend prêt'

    # --- 3. Interface --------------------------------------------------------------------------------
    Write-Host '3/4 Interface (build de production servi par vite preview)'
    if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules') -PathType Container)) {
        Write-Host '  node_modules absent : npm ci'
        $npm = Get-ToolPath 'npm.cmd'
        if (-not $npm) { throw 'npm introuvable' }
        $run = Invoke-LoggedProcess -FilePath $npm -Arguments @('ci') -WorkingDirectory $frontendDirectory `
            -LogPrefix (Join-Path $logDirectory 'npm-ci') -OnHeartbeat { Write-Host '  ... npm ci en cours' }
        if ($run.ExitCode -ne 0) {
            Write-LogTail @($run.OutFile, $run.ErrFile) 30
            throw "npm ci a échoué (code $($run.ExitCode))"
        }
    }
    $viteScript = Join-Path $frontendDirectory 'node_modules\vite\bin\vite.js'
    $playwrightCli = Join-Path $frontendDirectory 'node_modules\@playwright\test\cli.js'
    if (-not (Test-Path -LiteralPath $viteScript -PathType Leaf)) { throw 'vite introuvable dans frontend/node_modules (npm ci)' }
    if (-not (Test-Path -LiteralPath $playwrightCli -PathType Leaf)) { throw '@playwright/test introuvable dans frontend/node_modules (npm ci)' }
    Set-ProcessEnvironment 'VITE_API_BASE_URL' $apiUrl
    $run = Invoke-LoggedProcess -FilePath $node -Arguments @($viteScript, 'build', '--outDir', $webDirectory, '--emptyOutDir') `
        -WorkingDirectory $frontendDirectory -LogPrefix (Join-Path $logDirectory 'vite-build')
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile) 30
        throw "le build de l'interface a échoué (code $($run.ExitCode))"
    }
    $webOut = Join-Path $logDirectory 'vite-preview.out.log'
    $webErr = Join-Path $logDirectory 'vite-preview.err.log'
    $webArguments = @($viteScript, 'preview', '--outDir', $webDirectory, '--host', 'localhost', '--port', [string]$WebPort, '--strictPort')
    $webProcess = Start-Process -FilePath $node -ArgumentList (($webArguments | ForEach-Object { ConvertTo-QuotedArgument $_ }) -join ' ') `
        -WorkingDirectory $frontendDirectory -RedirectStandardOutput $webOut -RedirectStandardError $webErr -NoNewWindow -PassThru
    $null = $webProcess.Handle
    Wait-Http "$baseUrl/" 60 $webProcess @($webOut, $webErr)
    Write-Host "  interface prête sur $baseUrl"

    # --- 4. Playwright -------------------------------------------------------------------------------
    Write-Host '4/4 Playwright'
    Set-ProcessEnvironment 'E2E_BASE_URL' $baseUrl
    Set-ProcessEnvironment 'E2E_API_URL' $apiUrl
    Set-ProcessEnvironment 'E2E_PASSWORD' $e2ePassword
    if ($browserChannel) { Set-ProcessEnvironment 'E2E_BROWSER_CHANNEL' $browserChannel }
    if ($probeAddress) {
        Set-ProcessEnvironment 'E2E_PROBE_TCP_HOST' $probeAddress
        Set-ProcessEnvironment 'E2E_PROBE_TCP_PORT' ([string]$ApiPort)
        Write-Host "  cible de la sonde TCP : ${probeAddress}:$ApiPort (adresse non locale du poste)"
    }
    else {
        Set-ProcessEnvironment 'E2E_PROBE_TCP_SKIP' '1'
        Write-Host '  aucune adresse IPv4 non locale : le test de la sonde TCP sera ignoré (compté « skipped »)' -ForegroundColor Yellow
    }

    $playwrightLog = Join-Path $logDirectory 'playwright.log'
    Push-Location $frontendDirectory
    try {
        $global:LASTEXITCODE = 0
        & $node $playwrightCli test @PlaywrightArguments | Tee-Object -FilePath $playwrightLog
        $playwrightCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    $counts = @{ passed = 0; failed = 0; skipped = 0; flaky = 0 }
    foreach ($line in @(Get-LogLines @($playwrightLog))) {
        if ($line -match '^\s*(\d+) (passed|failed|skipped|flaky)\b') { $counts[$Matches[2]] = [int]$Matches[1] }
    }
    $summary = "{0} passed, {1} failed, {2} skipped, {3} flaky (Playwright, pile native, sans Docker)" -f $counts['passed'], $counts['failed'], $counts['skipped'], $counts['flaky']
    Write-Host "Résultat : $summary"
    if (($playwrightCode -eq 0) -and ($counts['passed'] -gt 0) -and ($counts['failed'] -eq 0)) {
        $exitCode = 0
    }
    else {
        if ($playwrightCode -eq 0) { $summary = "$summary - code 0 mais aucun test réussi : résultat refusé" }
        $exitCode = 1
    }
}
catch {
    $summary = "échec de la pile de test : $($_.Exception.Message)"
    Write-Host $summary -ForegroundColor Red
    $exitCode = 1
}
finally {
    Write-Host 'Arrêt de la pile de test'
    Stop-Started $webProcess
    Stop-Started $backendProcess
    if ($postgresStarted) {
        try {
            $null = Invoke-LoggedProcess -FilePath (Join-Path $postgresBin 'pg_ctl.exe') `
                -Arguments @('stop', '-D', $dataDirectory, '-m', 'immediate', '-w', '-t', '30') `
                -WorkingDirectory $workDirectory -LogPrefix (Join-Path $logDirectory 'pg_ctl-stop')
        }
        catch {
            Write-Host "  pg_ctl stop impossible : $($_.Exception.Message)" -ForegroundColor Yellow
        }
        # Dernier recours : processus postgres.exe qui utilisent encore le dossier de données jetable.
        try {
            $leftovers = @(Get-CimInstance -ClassName Win32_Process -Filter "Name = 'postgres.exe'" | Where-Object { $_.CommandLine -and ($_.CommandLine -like "*$dataDirectory*") })
            foreach ($leftover in $leftovers) { Stop-Process -Id $leftover.ProcessId -Force }
        }
        catch {
            Write-Host "  vérification des processus postgres.exe impossible : $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    Restore-ProcessEnvironment

    $toRemove = @($dataDirectory, $webDirectory)
    if ($exitCode -eq 0) { $toRemove = @($workDirectory) }
    foreach ($path in $toRemove) {
        for ($attempt = 1; $attempt -le 5; $attempt++) {
            if (-not (Test-Path -LiteralPath $path)) { break }
            try { Remove-Item -LiteralPath $path -Recurse -Force; break }
            catch { Start-Sleep -Seconds 1 }
        }
        if (Test-Path -LiteralPath $path) { Write-Host "  dossier temporaire non supprimé : $path" -ForegroundColor Yellow }
    }
    if ($exitCode -ne 0) { Write-Host "Journaux de la pile conservés : $logDirectory" -ForegroundColor Yellow }
}

if ($SummaryFile) { Set-Content -LiteralPath $SummaryFile -Value $summary -Encoding UTF8 }
exit $exitCode
