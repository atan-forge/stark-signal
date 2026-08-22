[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('tiny-multilingual', 'base-multilingual', 'base-q5', 'small-q5')] [string] $CandidateId,
    [Parameter(Mandatory)] [string] $SourceModel,
    [string] $StageDirectory = "local-models/benchmark-selected"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$model = (Resolve-Path -LiteralPath $SourceModel).Path
$stage = [IO.Path]::GetFullPath((Join-Path (Get-Location) $StageDirectory))
$models = Join-Path $stage 'models'
New-Item -ItemType Directory -Force -Path $models | Out-Null
$hash = (Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $model).Length
$destination = Join-Path $models 'selected-model.bin'
Copy-Item -LiteralPath $model -Destination $destination -Force
if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) { throw 'Model copy verification failed.' }

$metadata = @{
    'tiny-multilingual' = @{ sourceUrl = 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin'; upstreamSha1 = 'bd577a113a864445d4c299885e0cb97d4ba92b5f'; quantization = 'F16' }
    'base-multilingual' = @{ sourceUrl = 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin'; upstreamSha1 = '465707469ff3a37a2b9b8d8f89f2f99de7299dac'; quantization = 'F16' }
    'base-q5' = @{ sourceUrl = 'https://huggingface.co/ggerganov/whisper.cpp/'; upstreamSha1 = ''; quantization = 'Q5' }
    'small-q5' = @{ sourceUrl = 'https://huggingface.co/ggerganov/whisper.cpp/'; upstreamSha1 = ''; quantization = 'Q5' }
}[$CandidateId]
[ordered]@{ candidateId = $CandidateId; sha256 = $hash; byteCount = $size; modelFormat = 'GGML'; quantization = $metadata.quantization; sourceUrl = $metadata.sourceUrl; upstreamSha1 = $metadata.upstreamSha1; modelLicense = 'MIT'; engineRevision = 'whisper.cpp-1.8.6'; stagedAtUtc = [DateTime]::UtcNow.ToString('o') } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'model-provenance.local.json') -Encoding utf8
Write-Host "Verified and staged $CandidateId ($size bytes)."
Write-Host "Build with: .\gradlew.bat :app:assembleBenchmark '-PstarkBenchmarkModelId=$CandidateId' '-PstarkBenchmarkModelSha256=$hash' '-PstarkBenchmarkAssetsDir=$stage' --no-daemon"
