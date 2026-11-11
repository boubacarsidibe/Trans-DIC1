# Exporte chaque diapositive du support en PNG avec PowerPoint lui-même (rendu fidèle : polices, formes,
# médias), pour la relecture visuelle. Usage :
#   powershell -ExecutionPolicy Bypass -File docs/soutenance/deck/export-slides.ps1 [-Deck <pptx>] [-Out <dossier>] [-Width 1600]
param(
    [string]$Deck = (Join-Path $PSScriptRoot '..\..\..\deliverables\Trans-DIC1-soutenance.pptx'),
    [string]$Out = (Join-Path $PSScriptRoot 'build\render'),
    [int]$Width = 1600
)
$ErrorActionPreference = 'Stop'
$Deck = (Resolve-Path $Deck).Path
New-Item -ItemType Directory -Force -Path $Out | Out-Null
Get-ChildItem -Path $Out -Filter 'slide-*.png' -ErrorAction SilentlyContinue | Remove-Item -Force
$app = New-Object -ComObject PowerPoint.Application
try {
    # Ouverture en lecture seule, sans fenêtre.
    $pres = $app.Presentations.Open($Deck, $true, $false, $false)
    $height = [int]($Width * $pres.PageSetup.SlideHeight / $pres.PageSetup.SlideWidth)
    foreach ($slide in $pres.Slides) {
        $file = Join-Path $Out ('slide-{0:D2}.png' -f $slide.SlideIndex)
        $slide.Export($file, 'PNG', $Width, $height)
    }
    Write-Output ("{0} diapositives exportées dans {1}" -f $pres.Slides.Count, (Resolve-Path $Out).Path)
    $pres.Close()
} finally {
    $app.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($app) | Out-Null
}
