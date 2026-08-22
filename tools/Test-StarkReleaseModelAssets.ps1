[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $AssetDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$stage = (Resolve-Path -LiteralPath $AssetDirectory).Path
$model = Join-Path $stage 'models\selected-model.bin'
$provenancePath = Join-Path $stage 'models\model-provenance.json'

if (-not (Test-Path -LiteralPath $model -PathType Leaf)) { throw "Missing staged release model: $model" }
if (-not (Test-Path -LiteralPath $provenancePath -PathType Leaf)) { throw "Missing staged release model provenance: $provenancePath" }
if (Test-Path -LiteralPath (Join-Path $stage 'benchmark-pack')) { throw 'Release assets must not include a benchmark pack.' }

$provenance = Get-Content -LiteralPath $provenancePath -Raw | ConvertFrom-Json
if ([string]$provenance.candidateId -ne 'base-multilingual') { throw 'The release build must stage the Base multilingual model.' }
if ([string]$provenance.sha256 -notmatch '^[0-9a-fA-F]{64}$') { throw 'Model provenance has no valid SHA-256.' }
if ([long]$provenance.byteCount -le 0) { throw 'Model provenance has no valid byte count.' }
foreach ($field in 'sourceUrl', 'modelLicense', 'engineRevision') {
    if ([string]::IsNullOrWhiteSpace([string]$provenance.$field)) { throw "Model provenance field is missing: $field" }
}

$actual = (Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne ([string]$provenance.sha256).ToLowerInvariant()) { throw 'Staged release model does not match its provenance SHA-256.' }
if ((Get-Item -LiteralPath $model).Length -ne [long]$provenance.byteCount) { throw 'Staged release model byte count does not match its provenance.' }

Write-Host "Release Base model verified ($actual)."
