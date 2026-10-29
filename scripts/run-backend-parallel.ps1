[CmdletBinding()]
param(
    [int]$Port = 8090,
    [string]$Schema = 'run_demo',
    [string]$EnvFile,
    [string]$RunFile
)

# Démarre le backend en parallèle d'une instance déjà lancée (port 8080 par défaut, 8081 et 8082 sont souvent pris) :
#   - port différent (8090) pour ne pas entrer en conflit ;
#   - schéma PostgreSQL séparé (run_demo) dans la même base : les migrations de cette version n'abîment
#     pas les tables de l'autre instance ;
#   - compte administrateur de démonstration dont le mot de passe est généré une fois et gardé dans
#     .env.run (ignoré par Git), jamais affiché.
# Les valeurs sensibles (mot de passe de la base, secret JWT) viennent de .env, comme d'habitude.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Racine du dépôt : dossier parent du script ; à défaut (lancement par un outil), dossier courant.
$Root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }
if (-not $EnvFile) { $EnvFile = Join-Path $Root '.env' }
if (-not $RunFile) { $RunFile = Join-Path $Root '.env.run' }

function Read-EnvFile([string]$Path) {
    $values = @{}
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2].Trim() }
        }
    }
    return $values
}

$settings = Read-EnvFile $EnvFile
foreach ($required in 'DATABASE_PASSWORD', 'JWT_SECRET') {
    if (-not $settings.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($settings[$required])) {
        throw "$required est vide dans .env : lancez scripts/init-dev-env.ps1 d'abord."
    }
}
foreach ($pair in $settings.GetEnumerator()) {
    if (-not [string]::IsNullOrEmpty($pair.Value)) { Set-Item -Path "Env:$($pair.Key)" -Value $pair.Value }
}

# Compte de démonstration : créé une fois, mot de passe aléatoire conservé dans .env.run.
$run = Read-EnvFile $RunFile
if (-not $run.ContainsKey('RUN_ADMIN_PASSWORD')) {
    $bytes = New-Object byte[] 18
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    $password = 'Demo-' + ([Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', 'x')
    Set-Content -LiteralPath $RunFile -Encoding UTF8 -Value @(
        '# Compte administrateur de l''instance de démonstration (ne pas versionner).',
        'RUN_ADMIN_USERNAME=admin-demo',
        'RUN_ADMIN_EMAIL=admin-demo@example.test',
        "RUN_ADMIN_PASSWORD=$password"
    )
    $run = Read-EnvFile $RunFile
}

$databaseHost = if ($settings.ContainsKey('DATABASE_HOST')) { $settings['DATABASE_HOST'] } else { 'localhost' }
$databasePort = if ($settings.ContainsKey('DATABASE_PORT')) { $settings['DATABASE_PORT'] } else { '5432' }
$databaseName = if ($settings.ContainsKey('DATABASE_NAME')) { $settings['DATABASE_NAME'] } else { 'trans_dic1' }

$env:APP_PORT = "$Port"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${databaseHost}:${databasePort}/${databaseName}?currentSchema=$Schema"
$env:SPRING_FLYWAY_SCHEMAS = $Schema
$env:BOOTSTRAP_ADMIN_USERNAME = $run['RUN_ADMIN_USERNAME']
$env:BOOTSTRAP_ADMIN_EMAIL = $run['RUN_ADMIN_EMAIL']
$env:BOOTSTRAP_ADMIN_PASSWORD = $run['RUN_ADMIN_PASSWORD']
# Navigateur sur http://localhost : le cookie de renouvellement ne peut pas exiger HTTPS ici.
$env:REFRESH_COOKIE_SECURE = 'false'
$env:CORS_ALLOWED_ORIGINS = 'http://localhost:5173'
# Mesures simulées toutes les 10 s : la démonstration a des courbes sans agent ni SNMP.
$env:COLLECTION_SIMULATED_ENABLED = 'true'
$env:COLLECTION_SIMULATED_INTERVAL_MS = '10000'

# Le projet est en Java 21 : une variable JAVA_HOME restée sur un JDK plus ancien fait échouer Maven.
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($javaCommand) {
    $major = (Get-Item -LiteralPath $javaCommand.Source).VersionInfo.ProductMajorPart
    if ($major -ge 21) { $env:JAVA_HOME = Split-Path (Split-Path $javaCommand.Source) }
}

Write-Host "Backend de démonstration sur http://localhost:$Port (schéma $Schema)." -ForegroundColor Green
Write-Host "Compte : $($run['RUN_ADMIN_USERNAME']) - mot de passe dans .env.run" -ForegroundColor Green

Set-Location (Join-Path $Root 'backend')
& .\mvnw.cmd -B spring-boot:run
