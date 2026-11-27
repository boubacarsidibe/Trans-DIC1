param([string]$Sql)
# Requête sur le schéma ha_test (psql dans le PATH, ou variable PSQL) ; le mot de passe vient de .env et n'est pas affiché.
$settings = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.env') -Encoding UTF8) {
  if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2].Trim() }
}
$env:PGPASSWORD = $settings['DATABASE_PASSWORD']
$user = if ($settings['DATABASE_USER']) { $settings['DATABASE_USER'] } elseif ($settings['DATABASE_USERNAME']) { $settings['DATABASE_USERNAME'] } else { 'trans_dic1' }
$db = if ($settings['DATABASE_NAME']) { $settings['DATABASE_NAME'] } else { 'trans_dic1' }
$psql = if ($env:PSQL) { $env:PSQL } else { 'psql' }
& $psql -h localhost -U $user -d $db -v ON_ERROR_STOP=1 -c "SET search_path TO ha_test; $Sql"
Remove-Item Env:PGPASSWORD
