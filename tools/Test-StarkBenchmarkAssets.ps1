[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $AssetDirectory,
    [Parameter(Mandatory)] [string] $CandidateId,
    [Parameter(Mandatory)] [string] $ExpectedSha256
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$stage = (Resolve-Path -LiteralPath $AssetDirectory).Path
$model = Join-Path $stage 'models\selected-model.bin'
$manifest = Join-Path $stage 'benchmark-pack\manifest.json'
$attribution = Join-Path $stage 'benchmark-pack\ATTRIBUTION.md'
$provenancePath = Join-Path $stage 'model-provenance.local.json'

if (-not (Test-Path -LiteralPath $model -PathType Leaf)) { throw "Missing staged model: $model" }
if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw "Missing benchmark manifest: $manifest" }
if (-not (Test-Path -LiteralPath $attribution -PathType Leaf)) { throw "Missing benchmark attribution: $attribution" }
if (-not (Test-Path -LiteralPath $provenancePath -PathType Leaf)) { throw "Missing model provenance: $provenancePath" }
if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$') { throw 'Expected SHA-256 must contain exactly 64 hexadecimal characters.' }

$provenance = Get-Content -LiteralPath $provenancePath -Raw | ConvertFrom-Json
if ($provenance.candidateId -ne $CandidateId) { throw 'Staged candidate ID does not match the requested candidate.' }
if ($provenance.sha256.ToLowerInvariant() -ne $ExpectedSha256.ToLowerInvariant()) { throw 'Provenance hash does not match the requested hash.' }
$actual = (Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash
if ($actual -ne $ExpectedSha256) { throw 'Staged model bytes do not match the requested SHA-256.' }

$pack = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
if ([int]$pack.schemaVersion -lt 3) { throw 'Benchmark pack schema is obsolete.' }
if (@($pack.cases).Count -eq 0) { throw 'Benchmark pack contains no cases.' }
Write-Host "Benchmark assets verified for $CandidateId ($($actual.ToLowerInvariant()))."
