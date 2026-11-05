<#
.SYNOPSIS
    Contrôle local type intégration continue de Trans-DIC1 (remplace les workflows GitHub Actions supprimés, risque R2).

.DESCRIPTION
    Enchaîne, dans cet ordre : contrôle du dépôt, validation statique des fichiers Compose, tests du backend
    (mvnw verify : tests, JaCoCo, Spotless), lint, tests et build du frontend, puis les étapes facultatives
    demandées. Chaque étape affiche sa durée ; un tableau final donne le statut de toutes les étapes
    (PASSED, FAILED, SKIPPED, NOT RUN). Une étape ignorée n'est jamais comptée comme réussie.
    Le script s'arrête à la première étape en échec, sauf avec -ContinueOnError.
    Code de sortie : 0 si aucune étape exécutée n'a échoué, 1 sinon.

    Aucune étape ne démarre Docker. La validation Compose est une lecture statique des fichiers (« docker compose
    config »), qui n'a pas besoin du démon ; elle est ignorée si l'exécutable docker est absent.
    Compatible Windows PowerShell 5.1 et PowerShell 7. Détails : docs/devops/verification-locale.md.

.PARAMETER ContinueOnError
    Poursuit après une étape en échec au lieu de s'arrêter.
.PARAMETER SkipBackend
    Ignore l'étape backend (mvnw verify).
.PARAMETER SkipFrontend
    Ignore les étapes frontend (lint, tests, build).
.PARAMETER SkipCompose
    Ignore la validation statique des fichiers Compose.
.PARAMETER FrontendCoverage
    Lance « npm run test:coverage » (seuils de couverture de vite.config.js) à la place de « npm test ».
.PARAMETER WithAgentTests
    Lance les tests unitaires de l'agent (Python). Ignoré sous Windows : l'agent est prévu pour Linux.
.PARAMETER WithE2E
    Lance les tests Playwright sur une pile native (scripts/run-e2e-native.ps1) : PostgreSQL 16 installé, jetable.
.PARAMETER WithSecurityScans
    Lance gitleaks et trivy s'ils sont installés ; sinon les étapes sont ignorées.
#>
[CmdletBinding()]
param(
    [switch]$ContinueOnError,
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$SkipCompose,
    [switch]$FrontendCoverage,
    [switch]$WithAgentTests,
    [switch]$WithE2E,
    [switch]$WithSecurityScans
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptDirectory = $PSScriptRoot
$repositoryRoot = Split-Path -Parent $scriptDirectory
. (Join-Path $scriptDirectory 'lib\dev-tools.ps1')

$onWindows = Test-WindowsHost
$hostExe = Get-HostExecutablePath
$logDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('tdic1-verify-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

function New-Outcome([string]$Status, [string]$Detail = '') {
    return [pscustomobject]@{ Status = $Status; Detail = $Detail }
}

$script:steps = New-Object System.Collections.Generic.List[object]
function Add-Step([string]$Name, [scriptblock]$Action, [string]$SkipReason = '') {
    $script:steps.Add([pscustomobject]@{ Name = $Name; Action = $Action; SkipReason = $SkipReason })
}

$script:stepClock = [System.Diagnostics.Stopwatch]::StartNew()
$genericHeartbeat = {
    Write-Host ('  ... toujours en cours (' + (Format-Duration $script:stepClock.Elapsed.TotalSeconds) + ')')
}

# ---------------------------------------------------------------------------------------------------
# Fonctions propres aux étapes
# ---------------------------------------------------------------------------------------------------

# Totaux des rapports Surefire écrits depuis $Since. Les fichiers TEST-*.xml sont lus de préférence aux *.txt :
# le .txt d'une classe qui contient des classes @Nested indique « Tests run: 0 » et omet leurs tests (25 sur 521
# dans ce dépôt), alors que le .xml les compte.
function Get-SurefireTotals([string]$Directory, [datetime]$Since) {
    $totals = [pscustomobject]@{ Classes = 0; Run = 0; Failures = 0; Errors = 0; Skipped = 0 }
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { return $totals }
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -File | Where-Object { $_.LastWriteTime -ge $Since })
    foreach ($file in $files) {
        $reader = New-Object System.IO.StreamReader($file.FullName)
        try {
            $buffer = New-Object char[] 16384
            $count = $reader.Read($buffer, 0, $buffer.Length)
            $head = [string]::new($buffer, 0, $count)
        }
        finally {
            $reader.Dispose()
        }
        $root = [regex]::Match($head, '<testsuite\b[^>]*>')
        if (-not $root.Success) { continue }
        $tag = $root.Value
        $values = @{}
        foreach ($name in @('tests', 'failures', 'errors', 'skipped')) {
            $attribute = [regex]::Match($tag, '\b' + $name + '="(\d+)"')
            if (-not $attribute.Success) { $values[$name] = -1 } else { $values[$name] = [int]$attribute.Groups[1].Value }
        }
        if ($values['tests'] -lt 0) { continue }
        $totals.Classes += 1
        $totals.Run += $values['tests']
        $totals.Failures += [math]::Max(0, $values['failures'])
        $totals.Errors += [math]::Max(0, $values['errors'])
        $totals.Skipped += [math]::Max(0, $values['skipped'])
    }
    return $totals
}

# Dernier total que Maven lui-même a affiché (« Tests run: N, Failures: ... » sans nom de classe) ou $null.
function Get-MavenReportedTotals([string]$LogFile) {
    $pattern = '^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$'
    $last = @(Get-LogLines @($LogFile) | Where-Object { $_ -match $pattern } | Select-Object -Last 1)
    if ($last.Count -eq 0) { return $null }
    $null = $last[0] -match $pattern
    return [pscustomobject]@{ Run = [int]$Matches[1]; Failures = [int]$Matches[2]; Errors = [int]$Matches[3]; Skipped = [int]$Matches[4] }
}

function Get-JacocoSummary([string]$CsvPath, [datetime]$Since) {
    if (-not (Test-Path -LiteralPath $CsvPath -PathType Leaf)) { return $null }
    if ((Get-Item -LiteralPath $CsvPath).LastWriteTime -lt $Since) { return $null }
    $rows = @(Import-Csv -LiteralPath $CsvPath)
    if ($rows.Count -eq 0) { return $null }
    $lineMissed = [double](($rows | Measure-Object -Property LINE_MISSED -Sum).Sum)
    $lineCovered = [double](($rows | Measure-Object -Property LINE_COVERED -Sum).Sum)
    $branchMissed = [double](($rows | Measure-Object -Property BRANCH_MISSED -Sum).Sum)
    $branchCovered = [double](($rows | Measure-Object -Property BRANCH_COVERED -Sum).Sum)
    $lineRatio = 0.0
    $branchRatio = 0.0
    if (($lineMissed + $lineCovered) -gt 0) { $lineRatio = 100.0 * $lineCovered / ($lineMissed + $lineCovered) }
    if (($branchMissed + $branchCovered) -gt 0) { $branchRatio = 100.0 * $branchCovered / ($branchMissed + $branchCovered) }
    $invariant = [System.Globalization.CultureInfo]::InvariantCulture
    return [string]::Format($invariant, 'lignes {0:0.0} % (seuil 80), branches {1:0.0} % (seuil 60)', $lineRatio, $branchRatio)
}

function Get-NpmPath {
    $name = 'npm'
    if ($onWindows) { $name = 'npm.cmd' }
    $path = Get-ToolPath $name
    if (-not $path) { throw 'npm introuvable : installez Node.js 24 (champ engines de frontend/package.json).' }
    return $path
}

function Invoke-Npm([string[]]$NpmArguments, [string]$LogName) {
    Set-ProcessEnvironment 'NO_COLOR' '1'
    return Invoke-LoggedProcess -FilePath (Get-NpmPath) -Arguments $NpmArguments `
        -WorkingDirectory (Join-Path $repositoryRoot 'frontend') `
        -LogPrefix (Join-Path $logDirectory $LogName) -OnHeartbeat $genericHeartbeat
}

function Get-VitestCounts($Run) {
    $lines = @(Get-LogLines @($Run.OutFile, $Run.ErrFile))
    $testsLine = $lines | Where-Object { $_ -match '^\s*Tests\s+\d' } | Select-Object -Last 1
    $filesLine = $lines | Where-Object { $_ -match '^\s*Test Files\s+\d' } | Select-Object -Last 1
    $counts = [pscustomobject]@{ Found = $false; Passed = 0; Failed = 0; Skipped = 0; Files = '' }
    if ($null -eq $testsLine) { return $counts }
    $counts.Found = $true
    if ($testsLine -match '(\d+) passed') { $counts.Passed = [int]$Matches[1] }
    if ($testsLine -match '(\d+) failed') { $counts.Failed = [int]$Matches[1] }
    if ($testsLine -match '(\d+) skipped') { $counts.Skipped = [int]$Matches[1] }
    if ($null -ne $filesLine) { $counts.Files = ($filesLine -replace '^\s*Test Files\s+', '').Trim() }
    return $counts
}

function Find-Python {
    $names = @('python3', 'python')
    if ($onWindows) { $names = @('python', 'py') }
    foreach ($name in $names) {
        $path = Get-ToolPath $name
        # Le raccourci « python3.exe » du Microsoft Store n'est pas un interpréteur.
        if ($path -and ($path -notmatch '\\WindowsApps\\')) { return $path }
    }
    return $null
}

# ---------------------------------------------------------------------------------------------------
# Définition des étapes
# ---------------------------------------------------------------------------------------------------

Add-Step 'Dépôt : verify-repository.ps1' {
    $global:LASTEXITCODE = 0
    & $hostExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptDirectory 'verify-repository.ps1') | Out-Host
    if ($LASTEXITCODE -ne 0) { return New-Outcome 'FAILED' "verify-repository.ps1 : code de sortie $LASTEXITCODE" }
    return New-Outcome 'PASSED' 'fichiers requis, fichiers sensibles suivis, .env.example'
}

$composeSkip = ''
if ($SkipCompose) { $composeSkip = 'option -SkipCompose' }
Add-Step 'Compose : validation statique des fichiers' {
    $docker = Get-ToolPath 'docker'
    if (-not $docker) {
        return New-Outcome 'SKIPPED' 'exécutable docker absent (validation facultative : le projet n''exige pas Docker)'
    }
    # Valeurs factices : la validation n'a besoin ni d'un vrai .env ni du démon Docker.
    $envFile = Join-Path $logDirectory 'compose-factice.env'
    Set-Content -LiteralPath $envFile -Encoding ASCII -Value @(
        'DATABASE_PASSWORD=verification-locale-factice',
        'JWT_SECRET=verification-locale-factice-verification-locale-factice',
        'BACKUP_ENCRYPTION_KEY=verification-locale-factice',
        'KEYCLOAK_ADMIN_PASSWORD=verification-locale-factice',
        'PUBLIC_HOSTNAME=verification.invalid',
        'PUBLIC_URL=https://verification.invalid'
    )
    $checked = New-Object System.Collections.Generic.List[string]
    foreach ($composeFile in @('docker-compose.yml', 'docker-compose.prod.yml')) {
        $run = Invoke-LoggedProcess -FilePath $docker `
            -Arguments @('compose', '--env-file', $envFile, '--profile', '*', '-f', $composeFile, 'config', '-q') `
            -WorkingDirectory $repositoryRoot -LogPrefix (Join-Path $logDirectory ('compose-' + $composeFile))
        if ($run.ExitCode -ne 0) {
            Write-LogTail @($run.OutFile, $run.ErrFile) 15
            return New-Outcome 'FAILED' "$composeFile invalide (code $($run.ExitCode))"
        }
        Write-Host "  $composeFile : valide"
        $checked.Add($composeFile)
    }
    return New-Outcome 'PASSED' ($checked -join ', ')
} $composeSkip

$backendSkip = ''
if ($SkipBackend) { $backendSkip = 'option -SkipBackend' }
Add-Step 'Backend : mvnw verify (tests, JaCoCo, Spotless)' {
    $jdk = Find-Jdk21
    $current = $env:JAVA_HOME
    if (-not $current) { $current = '(non défini)' }
    if (-not $jdk) {
        throw ("JDK 21 introuvable (JAVA_HOME = $current ; recherché aussi dans C:\Program Files\Eclipse Adoptium\jdk-21*). " +
            'Installez Eclipse Temurin 21 ou définissez JAVA_HOME vers un JDK 21.')
    }
    if ($current -eq $jdk) {
        Write-Host "  JDK 21 : $jdk"
    }
    else {
        Write-Host "  JAVA_HOME ($current) n'est pas un JDK 21 : utilisation de $jdk pour cette exécution"
    }
    Set-ProcessEnvironment 'JAVA_HOME' $jdk

    $backend = Join-Path $repositoryRoot 'backend'
    $wrapper = 'mvnw'
    if ($onWindows) { $wrapper = 'mvnw.cmd' }
    $reports = Join-Path $backend 'target\surefire-reports'
    $startedAt = (Get-Date).AddSeconds(-2)
    $heartbeat = {
        $finished = 0
        if (Test-Path -LiteralPath $reports -PathType Container) {
            $finished = @(Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' -File | Where-Object { $_.LastWriteTime -ge $startedAt }).Count
        }
        Write-Host ("  ... toujours en cours ({0}), {1} classes de test terminées" -f (Format-Duration $script:stepClock.Elapsed.TotalSeconds), $finished)
    }
    Write-Host '  mvnw -B verify (sans -q ; sortie complète dans le journal ci-dessous)'
    $run = Invoke-LoggedProcess -FilePath (Join-Path $backend $wrapper) `
        -Arguments @('-B', '--no-transfer-progress', 'verify') `
        -WorkingDirectory $backend -LogPrefix (Join-Path $logDirectory 'backend') -OnHeartbeat $heartbeat
    Write-Host "  Journal Maven : $($run.OutFile)"

    $totals = Get-SurefireTotals $reports $startedAt
    Write-Host ('  Tests (rapports Surefire TEST-*.xml) : {0} exécutés, {1} échecs, {2} erreurs, {3} ignorés, dans {4} classes' -f `
            $totals.Run, $totals.Failures, $totals.Errors, $totals.Skipped, $totals.Classes)
    $mavenTotals = Get-MavenReportedTotals $run.OutFile
    if ($null -ne $mavenTotals) {
        Write-Host ('  Total affiché par Maven : {0} exécutés, {1} échecs, {2} erreurs, {3} ignorés' -f `
                $mavenTotals.Run, $mavenTotals.Failures, $mavenTotals.Errors, $mavenTotals.Skipped)
    }
    $coverage = Get-JacocoSummary (Join-Path $backend 'target\site\jacoco\jacoco.csv') $startedAt
    if ($coverage) { Write-Host "  Couverture JaCoCo : $coverage" }

    if ($run.ExitCode -ne 0) {
        $errorLines = @(Get-LogLines @($run.OutFile) | Where-Object { $_ -match '^\[ERROR\]' } | Select-Object -First 25)
        Write-Host '  Extrait des erreurs Maven :'
        if ($errorLines.Count -gt 0) {
            foreach ($line in $errorLines) { Write-Host ('    ' + $line) }
        }
        else {
            Write-LogTail @($run.OutFile, $run.ErrFile) 30
        }
        return New-Outcome 'FAILED' "mvnw verify a échoué (code $($run.ExitCode))"
    }
    if ($totals.Run -eq 0) {
        return New-Outcome 'FAILED' 'mvnw verify a réussi mais aucun test n''a été exécuté (aucun rapport Surefire récent)'
    }
    if (($null -ne $mavenTotals) -and ($mavenTotals.Run -ne $totals.Run)) {
        return New-Outcome 'FAILED' "totaux incohérents : Maven annonce $($mavenTotals.Run) tests, les rapports Surefire en comptent $($totals.Run)"
    }
    if (($totals.Failures -gt 0) -or ($totals.Errors -gt 0)) {
        return New-Outcome 'FAILED' "$($totals.Failures) échec(s), $($totals.Errors) erreur(s) dans les rapports Surefire"
    }
    $detail = "$($totals.Run) tests, 0 échec, 0 erreur, $($totals.Skipped) ignoré(s)"
    if ($coverage) { $detail = "$detail ; JaCoCo $coverage" }
    return New-Outcome 'PASSED' $detail
} $backendSkip

$frontendSkip = ''
if ($SkipFrontend) { $frontendSkip = 'option -SkipFrontend' }
if ((-not $SkipFrontend) -and (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'frontend\node_modules') -PathType Container))) {
    Add-Step 'Frontend : npm ci (node_modules absent)' {
        $run = Invoke-Npm @('ci') 'frontend-ci'
        if ($run.ExitCode -ne 0) {
            Write-LogTail @($run.OutFile, $run.ErrFile) 30
            return New-Outcome 'FAILED' "npm ci a échoué (code $($run.ExitCode))"
        }
        return New-Outcome 'PASSED' 'dépendances installées depuis package-lock.json'
    }
}

Add-Step 'Frontend : npm run lint' {
    $run = Invoke-Npm @('run', 'lint') 'frontend-lint'
    $lines = @(Get-LogLines @($run.OutFile, $run.ErrFile))
    # En sortie non interactive, oxlint n'affiche pas de résumé : les diagnostics sont comptés ici.
    $warnings = @($lines | Where-Object { $_ -match ': warning ' }).Count
    $errors = @($lines | Where-Object { $_ -match ': error ' }).Count
    Write-Host "  oxlint : $errors erreur(s), $warnings avertissement(s)"
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile) 40
        return New-Outcome 'FAILED' "oxlint : code de sortie $($run.ExitCode), $errors erreur(s)"
    }
    return New-Outcome 'PASSED' "$errors erreur(s), $warnings avertissement(s)"
} $frontendSkip

$testName = 'Frontend : npm test'
$testArguments = @('test')
if ($FrontendCoverage) {
    $testName = 'Frontend : npm run test:coverage'
    $testArguments = @('run', 'test:coverage')
}
Add-Step $testName {
    $run = Invoke-Npm $testArguments 'frontend-test'
    $counts = Get-VitestCounts $run
    if ($counts.Found) {
        Write-Host ('  Vitest : {0} réussis, {1} échoués, {2} ignorés (fichiers : {3})' -f $counts.Passed, $counts.Failed, $counts.Skipped, $counts.Files)
    }
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile) 40
        return New-Outcome 'FAILED' "Vitest : code de sortie $($run.ExitCode)"
    }
    if (-not $counts.Found) {
        return New-Outcome 'FAILED' 'totaux Vitest introuvables dans la sortie : impossible de confirmer que des tests ont tourné'
    }
    if (($counts.Passed + $counts.Failed) -eq 0) {
        return New-Outcome 'FAILED' 'Vitest n''a exécuté aucun test'
    }
    if ($counts.Failed -gt 0) { return New-Outcome 'FAILED' "$($counts.Failed) test(s) en échec" }
    return New-Outcome 'PASSED' "$($counts.Passed) tests, $($counts.Skipped) ignoré(s)"
} $frontendSkip

Add-Step 'Frontend : npm run build' {
    $run = Invoke-Npm @('run', 'build') 'frontend-build'
    $built = @(Get-LogLines @($run.OutFile, $run.ErrFile) | Where-Object { $_ -match 'built in' } | Select-Object -Last 1 |
            ForEach-Object { [regex]::Match($_, 'built in [0-9.]+m?s').Value })
    foreach ($line in $built) { Write-Host ('  ' + $line) }
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile) 40
        return New-Outcome 'FAILED' "vite build : code de sortie $($run.ExitCode)"
    }
    $detail = ''
    if ($built.Count -gt 0) { $detail = $built[0] }
    return New-Outcome 'PASSED' $detail
} $frontendSkip

$agentSkip = ''
if (-not $WithAgentTests) { $agentSkip = 'option -WithAgentTests absente' }
Add-Step 'Agent : tests unitaires (Python)' {
    if ($onWindows) {
        return New-Outcome 'SKIPPED' 'l''agent est prévu pour Linux (droits 600, os.statvfs) : ses tests échouent sous Windows ; à lancer sur Linux (python -m unittest discover -s tests -t . dans agent/)'
    }
    $python = Find-Python
    if (-not $python) { return New-Outcome 'SKIPPED' 'Python introuvable' }
    $run = Invoke-LoggedProcess -FilePath $python -Arguments @('-m', 'unittest', 'discover', '-s', 'tests', '-t', '.') `
        -WorkingDirectory (Join-Path $repositoryRoot 'agent') -LogPrefix (Join-Path $logDirectory 'agent') `
        -OnHeartbeat $genericHeartbeat
    $lines = @(Get-LogLines @($run.OutFile, $run.ErrFile))
    $ranLine = $lines | Where-Object { $_ -match '^Ran (\d+) tests?' } | Select-Object -Last 1
    $ran = 0
    if (($null -ne $ranLine) -and ($ranLine -match '^Ran (\d+) tests?')) { $ran = [int]$Matches[1] }
    Write-Host "  unittest : $ran test(s)"
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile) 30
        return New-Outcome 'FAILED' "unittest : code de sortie $($run.ExitCode)"
    }
    if ($ran -eq 0) { return New-Outcome 'FAILED' 'aucun test de l''agent exécuté' }
    return New-Outcome 'PASSED' "$ran tests"
} $agentSkip

$securitySkip = ''
if (-not $WithSecurityScans) { $securitySkip = 'option -WithSecurityScans absente' }
Add-Step 'Sécurité : gitleaks detect' {
    $gitleaks = Get-ToolPath 'gitleaks'
    if (-not $gitleaks) { return New-Outcome 'SKIPPED' 'gitleaks absent du PATH (non installé : aucun scan de secrets effectué)' }
    $run = Invoke-LoggedProcess -FilePath $gitleaks -Arguments @('detect', '--source', $repositoryRoot, '--redact', '--no-banner') `
        -WorkingDirectory $repositoryRoot -LogPrefix (Join-Path $logDirectory 'gitleaks') -OnHeartbeat $genericHeartbeat
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile) 30
        return New-Outcome 'FAILED' "gitleaks : code de sortie $($run.ExitCode) (secret détecté, ou erreur d'exécution : voir le journal)"
    }
    return New-Outcome 'PASSED' 'aucun secret détecté (code, historique)'
} $securitySkip

Add-Step 'Sécurité : trivy fs' {
    $trivy = Get-ToolPath 'trivy'
    if (-not $trivy) { return New-Outcome 'SKIPPED' 'trivy absent du PATH (non installé : aucun scan de vulnérabilités effectué)' }
    $run = Invoke-LoggedProcess -FilePath $trivy `
        -Arguments @('fs', '--scanners', 'vuln', '--severity', 'HIGH,CRITICAL', '--ignore-unfixed', '--exit-code', '1', '--no-progress',
            '--skip-dirs', 'node_modules', '--skip-dirs', 'backend/target', '--skip-dirs', 'frontend/dist', '.') `
        -WorkingDirectory $repositoryRoot -LogPrefix (Join-Path $logDirectory 'trivy') -OnHeartbeat $genericHeartbeat
    if ($run.ExitCode -ne 0) {
        Write-LogTail @($run.OutFile, $run.ErrFile) 40
        return New-Outcome 'FAILED' "trivy : code de sortie $($run.ExitCode) (faille HIGH/CRITICAL corrigeable, ou base de vulnérabilités injoignable : voir le journal)"
    }
    return New-Outcome 'PASSED' 'aucune faille HIGH ou CRITICAL corrigeable'
} $securitySkip

$e2eSkip = ''
if (-not $WithE2E) { $e2eSkip = 'option -WithE2E absente' }
Add-Step 'Bout en bout : Playwright (pile native)' {
    $e2eScript = Join-Path $scriptDirectory 'run-e2e-native.ps1'
    $global:LASTEXITCODE = 0
    $check = @(& $hostExe -NoProfile -ExecutionPolicy Bypass -File $e2eScript -CheckPrerequisites)
    $code = $LASTEXITCODE
    $message = ''
    if ($check.Count -gt 0) { $message = ([string]$check[$check.Count - 1]) -replace '^SKIPPED:\s*', '' }
    if ($code -eq 3) { return New-Outcome 'SKIPPED' $message }
    if ($code -ne 0) { return New-Outcome 'FAILED' "contrôle des prérequis : code $code $message" }
    $summaryFile = Join-Path $logDirectory 'e2e-summary.txt'
    $global:LASTEXITCODE = 0
    & $hostExe -NoProfile -ExecutionPolicy Bypass -File $e2eScript -SummaryFile $summaryFile | Out-Host
    $code = $LASTEXITCODE
    $detail = ''
    if (Test-Path -LiteralPath $summaryFile -PathType Leaf) { $detail = (Get-Content -LiteralPath $summaryFile | Select-Object -First 1) }
    if ($code -eq 3) { return New-Outcome 'SKIPPED' $detail }
    if ($code -ne 0) { return New-Outcome 'FAILED' "run-e2e-native.ps1 : code $code $detail" }
    return New-Outcome 'PASSED' $detail
} $e2eSkip

# ---------------------------------------------------------------------------------------------------
# Exécution
# ---------------------------------------------------------------------------------------------------

$results = New-Object System.Collections.Generic.List[object]
$aborted = $false
$totalClock = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host ''
Write-Host "Trans-DIC1 : contrôle local ($($steps.Count) étapes, PowerShell $($PSVersionTable.PSVersion))" -ForegroundColor Cyan
Write-Host "Dépôt    : $repositoryRoot"
Write-Host "Journaux : $logDirectory"
Write-Host ''

try {
    $index = 0
    foreach ($step in $steps) {
        $index += 1
        $label = "[$index/$($steps.Count)] $($step.Name)"
        if ($aborted) {
            $results.Add([pscustomobject]@{ Name = $step.Name; Status = 'NOT RUN'; Seconds = 0.0; Detail = 'arrêt après une étape en échec (voir -ContinueOnError)' })
            continue
        }
        if ($step.SkipReason) {
            Write-Host "==> $label : SKIPPED ($($step.SkipReason))" -ForegroundColor Yellow
            $results.Add([pscustomobject]@{ Name = $step.Name; Status = 'SKIPPED'; Seconds = 0.0; Detail = $step.SkipReason })
            continue
        }

        Write-Host "==> $label" -ForegroundColor Cyan
        $script:stepClock = [System.Diagnostics.Stopwatch]::StartNew()
        $outcome = $null
        try {
            $emitted = @(& $step.Action)
            foreach ($item in $emitted) {
                $isOutcome = ($item -is [psobject]) -and ($null -ne $item.PSObject.Properties['Status']) -and ($null -ne $item.PSObject.Properties['Detail'])
                if ($isOutcome) { $outcome = $item }
                else { Write-Host ('  ' + ([string]$item).TrimEnd()) }
            }
            if ($null -eq $outcome) { $outcome = New-Outcome 'PASSED' }
        }
        catch {
            $outcome = New-Outcome 'FAILED' $_.Exception.Message
        }
        $script:stepClock.Stop()
        $seconds = $script:stepClock.Elapsed.TotalSeconds
        # Les variables d'une étape (JAVA_HOME, NO_COLOR) ne doivent pas fuiter vers les suivantes.
        Restore-ProcessEnvironment

        $color = 'Green'
        if ($outcome.Status -eq 'FAILED') { $color = 'Red' }
        if ($outcome.Status -eq 'SKIPPED') { $color = 'Yellow' }
        $line = "<== $($step.Name) : $($outcome.Status) en $(Format-Duration $seconds)"
        if ($outcome.Detail) { $line = "$line - $($outcome.Detail)" }
        Write-Host $line -ForegroundColor $color
        Write-Host ''

        $results.Add([pscustomobject]@{ Name = $step.Name; Status = $outcome.Status; Seconds = $seconds; Detail = $outcome.Detail })
        if (($outcome.Status -eq 'FAILED') -and (-not $ContinueOnError)) { $aborted = $true }
    }
}
finally {
    Restore-ProcessEnvironment
}

$totalClock.Stop()

Write-Host ''
Write-Host 'Récapitulatif' -ForegroundColor Cyan
Write-Host ('{0,-52} {1,-8} {2,12}  {3}' -f 'Étape', 'Statut', 'Durée', 'Détail')
Write-Host ('{0,-52} {1,-8} {2,12}  {3}' -f '-----', '------', '-----', '------')
foreach ($result in $results) {
    $color = 'Green'
    if ($result.Status -eq 'FAILED') { $color = 'Red' }
    if (($result.Status -eq 'SKIPPED') -or ($result.Status -eq 'NOT RUN')) { $color = 'Yellow' }
    $duration = ''
    if (($result.Status -eq 'PASSED') -or ($result.Status -eq 'FAILED')) { $duration = Format-Duration $result.Seconds }
    Write-Host ('{0,-52} {1,-8} {2,12}  {3}' -f $result.Name, $result.Status, $duration, $result.Detail) -ForegroundColor $color
}

$passed = @($results | Where-Object { $_.Status -eq 'PASSED' }).Count
$failed = @($results | Where-Object { $_.Status -eq 'FAILED' }).Count
$skipped = @($results | Where-Object { $_.Status -eq 'SKIPPED' }).Count
$notRun = @($results | Where-Object { $_.Status -eq 'NOT RUN' }).Count
Write-Host ''
Write-Host ("{0} réussie(s), {1} en échec, {2} ignorée(s), {3} non lancée(s), durée totale {4}. Journaux : {5}" -f `
        $passed, $failed, $skipped, $notRun, (Format-Duration $totalClock.Elapsed.TotalSeconds), $logDirectory)

$exitCode = 0
if ($failed -gt 0) {
    Write-Host 'RÉSULTAT : ÉCHEC' -ForegroundColor Red
    $exitCode = 1
}
elseif ($passed -eq 0) {
    Write-Host 'RÉSULTAT : aucune étape exécutée, rien n''a été vérifié.' -ForegroundColor Yellow
}
else {
    Write-Host 'RÉSULTAT : SUCCÈS (les étapes SKIPPED ne sont pas vérifiées)' -ForegroundColor Green
}
exit $exitCode
