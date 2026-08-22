[CmdletBinding()]
param(
    [string] $SdkRoot = 'C:\Android\Sdk',
    [string] $DebugApk = 'app/build/outputs/apk/debug/app-debug.apk',
    [string] $BenchmarkApk = 'app/build/outputs/apk/benchmark/app-benchmark.apk',
    [string] $ReleaseApk = 'app/build/outputs/apk/release/app-release-unsigned.apk'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Entries([string] $path) {
    $resolved = (Resolve-Path -LiteralPath $path).Path
    $zip = [IO.Compression.ZipFile]::OpenRead($resolved)
    try { return @($zip.Entries | ForEach-Object FullName) } finally { $zip.Dispose() }
}

$debugEntries = Entries $DebugApk
$hasBenchmarkApk = Test-Path -LiteralPath $BenchmarkApk
$benchmarkEntries = if ($hasBenchmarkApk) { @(Entries $BenchmarkApk) } else { @() }
$releaseEntries = Entries $ReleaseApk
if (@($debugEntries | Where-Object { $_ -like 'assets/models/*' -or $_ -like 'assets/benchmark-pack/*' }).Count -ne 0) {
    throw 'The ordinary debug APK contains a benchmark model or corpus.'
}
if ($hasBenchmarkApk) {
    if (@($benchmarkEntries | Where-Object { $_ -eq 'assets/models/selected-model.bin' }).Count -ne 1) {
        throw 'The benchmark APK must contain exactly one selected model.'
    }
    if (@($benchmarkEntries | Where-Object { $_ -like 'lib/*/libstark_whisper.so' }).Count -ne 1 -or
        @($benchmarkEntries | Where-Object { $_ -like 'lib/arm64-v8a/*' }).Count -eq 0) {
        throw 'The benchmark APK must contain one arm64 native transcription library.'
    }
    if (@($benchmarkEntries | Where-Object { $_ -match 'MIXED_REVIEW|benchmark-review' }).Count -ne 0) {
        throw 'A private corpus review artifact entered the benchmark APK.'
    }
}
if (@($releaseEntries | Where-Object { $_ -eq 'assets/models/selected-model.bin' }).Count -ne 1) {
    throw 'The release APK must contain exactly one selected Base model.'
}
if (@($releaseEntries | Where-Object { $_ -like 'assets/benchmark-pack/*' -or $_ -match 'MIXED_REVIEW|benchmark-review|diagnostic-schema' }).Count -ne 0) {
    throw 'The release APK contains benchmark-only material.'
}
if (@($releaseEntries | Where-Object { $_ -like 'lib/*/libstark_whisper.so' }).Count -ne 1 -or
    @($releaseEntries | Where-Object { $_ -like 'lib/arm64-v8a/*' }).Count -eq 0 -or
    @($releaseEntries | Where-Object { $_ -like 'lib/x86_64/*' }).Count -ne 0) {
    throw 'The release APK must contain exactly one arm64 native transcription library.'
}

$aapt = Join-Path $SdkRoot 'build-tools\37.0.0\aapt2.exe'
foreach ($apk in @($DebugApk, $ReleaseApk) + $(if ($hasBenchmarkApk) { @($BenchmarkApk) } else { @() })) {
    $permissions = & $aapt dump permissions (Resolve-Path -LiteralPath $apk).Path | Out-String
    if ($permissions -match 'android\.permission\.(INTERNET|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|CAMERA|ACCESS_[A-Z_]*LOCATION|READ_CONTACTS|AD_ID)') {
        throw "Forbidden permission found in $apk."
    }
}

$sensitiveLogging = rg -n --glob '*.kt' --glob '*.cpp' '(Log\.|__android_log_).*(transcript|referenceText|recognizedText|sourceUri|localPath)' app/src 2>$null
if ($LASTEXITCODE -eq 0 -and $sensitiveLogging) { throw "Potential sensitive logging found:`n$sensitiveLogging" }
if ($LASTEXITCODE -gt 1) { throw 'Sensitive-log scan could not run.' }

Write-Host 'APK content, permission, ABI, model, corpus-review, and sensitive-log policies passed.'
