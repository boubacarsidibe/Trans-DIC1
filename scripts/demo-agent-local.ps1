<#
.SYNOPSIS
Démonstration : installe l'agent sur ce poste par inscription automatique (ADR 0054), puis le lance.

.DESCRIPTION
Contre la plateforme locale (scripts/run-backend-parallel.ps1, http://localhost:8090) : crée un jeton
d'inscription (10 machines, 7 jours, adresses locales seulement, automatique), installe l'agent dans
le profil de l'utilisateur sans tâche planifiée (aucun droit administrateur requis), avec une mesure
toutes les 15 secondes, puis le lance au premier plan (Ctrl+C pour l'arrêter). Aucun jeton n'est
affiché. Pour un vrai poste ou serveur, utiliser install.ps1 en administrateur (tâche planifiée) ou le
rôle Ansible (docs/devops/deploiement-a-grande-echelle.md).
#>
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$base = 'http://localhost:8090/api/v1'
$demo = Join-Path $env:LOCALAPPDATA 'Trans-DIC1-Demo'
$pkg = Join-Path $demo 'paquet'
$app = Join-Path $demo 'agent'
$data = Join-Path $demo 'donnees'

# 1. Connexion administrateur (compte de démonstration, mot de passe lu dans .env.run).
$run = @{}
Get-Content (Join-Path $repo '.env.run') | ForEach-Object { if ($_ -match '^([A-Z_]+)=(.*)$') { $run[$Matches[1]] = $Matches[2].Trim() } }
$login = Invoke-RestMethod -Method Post -Uri "$base/auth/login" -ContentType 'application/json' `
    -Body (@{ username = $run['RUN_ADMIN_USERNAME']; password = $run['RUN_ADMIN_PASSWORD'] } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

# 2. Site de la démo et jeton d'inscription (10 machines, 7 jours, adresses locales, automatique).
$sites = Invoke-RestMethod -Uri "$base/sites?size=50" -Headers $headers
$list = if ($sites.content) { $sites.content } else { $sites }
$site = $list | Select-Object -First 1
$body = @{
    nom = 'Demo poste local'; siteId = $site.id; expireLe = (Get-Date).ToUniversalTime().AddDays(7).ToString('o')
    utilisationsMax = 10; plagesAutorisees = @('127.0.0.0/8', '::1/128'); mode = 'AUTO'; typeEquipement = 'SERVER'
} | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri "$base/inscriptions/jetons" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
Write-Host "Jeton d'inscription créé : « $($created.nom) » pour le site « $($site.name) » (valeur jamais affichée)."

# Une démonstration précédente est remplacée (l'agent repart d'une inscription neuve).
Remove-Item -Recurse -Force $app, $data -ErrorAction SilentlyContinue

# 3. Paquet : le code de l'agent et le script d'installation, comme dans le zip distribué.
Remove-Item -Recurse -Force $pkg -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $pkg | Out-Null
Copy-Item -Recurse (Join-Path $repo 'agent\trans_dic1_agent') $pkg
Copy-Item (Join-Path $repo 'agent\packaging\windows\*') $pkg
$tokenFile = Join-Path $demo 'jeton-inscription.txt'
[IO.File]::WriteAllText($tokenFile, $created.jeton)
icacls $tokenFile /inheritance:r /grant:r "$($env:USERDOMAIN)\$($env:USERNAME):(R,W)" | Out-Null

# 4. Installation (sans tâche planifiée : la session n'est pas administrateur).
& (Join-Path $pkg 'install.ps1') -ServerUrl 'http://localhost:8090' -EnrollTokenFile $tokenFile `
    -InstallDir $app -DataDir $data -NoTask -AllowInsecureHttp
Remove-Item -Force $tokenFile -ErrorAction SilentlyContinue

# 5. Une mesure toutes les 15 secondes : la démonstration bouge à l'écran.
$conf = Join-Path $data 'agent.conf'
# Lu et réécrit en UTF-8 sans BOM (Get-Content et Set-Content de PowerShell 5.1 ne le garantissent pas).
$utf8 = New-Object System.Text.UTF8Encoding($false)
$text = [IO.File]::ReadAllText($conf, $utf8) -replace '(?m)^interval = 60\r?$', 'interval = 15'
[IO.File]::WriteAllText($conf, $text, $utf8)

# 6. Lancement au premier plan, avec le Python retenu par install.ps1 (jamais le raccourci du Store).
$python = Get-Command python -All -ErrorAction SilentlyContinue |
    Where-Object { $_.Source -notlike '*\WindowsApps\*' } | Select-Object -First 1 -ExpandProperty Source
if (-not $python) { $python = Get-Command py -ErrorAction Stop | Select-Object -ExpandProperty Source }
Write-Host "Agent installé dans $app ; lancement (Ctrl+C pour arrêter)." -ForegroundColor Green
Set-Location $app
& $python -m trans_dic1_agent --config $conf
