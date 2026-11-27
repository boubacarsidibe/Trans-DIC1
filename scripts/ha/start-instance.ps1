param([int]$Port, [string]$Name)
# Usage : scripts/ha/start-instance.ps1 -Port 8091 -Name A  (JAVA_HOME sur un JDK 21, jar construit)
# Journaux et PID dans %TEMP%\trans-dic1-ha. Procédure complète : docs/devops/test-haute-disponibilite.md
# Démarre une instance du backend pour le test de haute disponibilité : même base, schéma ha_test.
# Les secrets viennent de .env et .env.run ; rien n'est affiché.
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
function Read-EnvFile([string]$Path) {
  $values = @{}
  foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2].Trim() }
  }
  return $values
}
$settings = Read-EnvFile (Join-Path $Root '.env')
foreach ($pair in $settings.GetEnumerator()) {
  if (-not [string]::IsNullOrEmpty($pair.Value)) { Set-Item -Path "Env:$($pair.Key)" -Value $pair.Value }
}
$run = Read-EnvFile (Join-Path $Root '.env.run')
$dbHost = if ($settings['DATABASE_HOST']) { $settings['DATABASE_HOST'] } else { 'localhost' }
$dbPort = if ($settings['DATABASE_PORT']) { $settings['DATABASE_PORT'] } else { '5432' }
$dbName = if ($settings['DATABASE_NAME']) { $settings['DATABASE_NAME'] } else { 'trans_dic1' }
$env:APP_PORT = "$Port"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${dbHost}:${dbPort}/${dbName}?currentSchema=ha_test"
$env:SPRING_FLYWAY_SCHEMAS = 'ha_test'
$env:BOOTSTRAP_ADMIN_USERNAME = $run['RUN_ADMIN_USERNAME']
$env:BOOTSTRAP_ADMIN_EMAIL = $run['RUN_ADMIN_EMAIL']
$env:BOOTSTRAP_ADMIN_PASSWORD = $run['RUN_ADMIN_PASSWORD']
$env:REFRESH_COOKIE_SECURE = 'false'
$env:BROKER_ENABLED = 'true'
$env:COLLECTION_SIMULATED_ENABLED = 'true'
$env:COLLECTION_SIMULATED_INTERVAL_MS = '10000'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$jar = Join-Path $Root 'backend\target\trans-dic1-backend-0.0.1-SNAPSHOT.jar'
$logs = Join-Path $env:TEMP 'trans-dic1-ha'
New-Item -ItemType Directory -Force $logs | Out-Null
$p = Start-Process -FilePath $java -ArgumentList @('-jar', $jar) -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $logs "$Name.log") -RedirectStandardError (Join-Path $logs "$Name.err")
Set-Content -LiteralPath (Join-Path $logs "$Name.pid") -Value $p.Id
"$Name pid=$($p.Id) port=$Port"
