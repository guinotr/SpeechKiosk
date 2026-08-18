param()

$ErrorActionPreference = "Stop"
$modelName = "sherpa-onnx-streaming-zipformer-fr-2023-04-14"
$downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$modelName.tar.bz2"
$projectRoot = $PSScriptRoot
$downloadDirectory = Join-Path $projectRoot "offline-model-download"
$archive = Join-Path $downloadDirectory "$modelName.tar.bz2"
$assetsDirectory = Join-Path $projectRoot "app\src\hybrid\assets"
$modelDirectory = Join-Path $assetsDirectory $modelName
$requiredFiles = @(
    "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
    "decoder-epoch-29-avg-9-with-averaged-model.onnx",
    "tokens.txt"
)

function Test-ModelComplete {
    foreach ($file in $requiredFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $modelDirectory $file))) { return $false }
    }
    $floatJoiner = Join-Path $modelDirectory "joiner-epoch-29-avg-9-with-averaged-model.onnx"
    $int8Joiner = Join-Path $modelDirectory "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx"
    return (Test-Path -LiteralPath $floatJoiner) -or (Test-Path -LiteralPath $int8Joiner)
}

if (Test-ModelComplete) {
    Write-Host "Le modèle local est déjà installé." -ForegroundColor Green
    exit 0
}

New-Item -ItemType Directory -Path $downloadDirectory,$assetsDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Write-Host "Téléchargement du modèle français sherpa-onnx (~140 Mo)..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archive
}

Write-Host "Extraction dans app/src/hybrid/assets..." -ForegroundColor Cyan
& tar -xjf $archive -C $assetsDirectory
if ($LASTEXITCODE -ne 0) { throw "L'extraction du modèle a échoué." }
if (-not (Test-ModelComplete)) { throw "Le téléchargement ne contient pas tous les fichiers attendus." }

Write-Host "Modèle installé. Compilez maintenant avec : .\gradlew.bat assembleHybridDebug" -ForegroundColor Green
