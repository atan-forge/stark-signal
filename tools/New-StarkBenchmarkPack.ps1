[CmdletBinding()]
param(
    [string] $EnglishArchive = 'benchmark-data/source/test-clean.tar.gz',
    [string] $IndonesianArchive = 'benchmark-data/source/1781716008901-cv-corpus-26.0-2026-06-12-id.tar.gz',
    [string] $HomostoriaArchive = 'benchmark-data/source/1763685862325-homostoria.tar.gz',
    [Parameter(Mandatory)] [string] $FfmpegPath,
    [string] $OutputDirectory = 'local-models/benchmark-selected/benchmark-pack',
    [ValidateRange(12, 60)] [int] $CasesPerLanguage = 30,
    [switch] $DownloadEnglish
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$EnglishUrl = 'https://www.openslr.org/resources/12/test-clean.tar.gz'
$EnglishMd5 = '32fa31d27d2e1cad72775fee3f4849a9'
$MinDurationSeconds = 2
$MaxDurationSeconds = 30

function Project-Path([string] $path) { [IO.Path]::GetFullPath((Join-Path (Get-Location) $path)) }
function Sha256([string] $path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Require-File([string] $path, [string] $message) { if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw $message }; (Resolve-Path -LiteralPath $path).Path }
function Timestamp-Seconds([string] $value) { [TimeSpan]::Parse($value, [Globalization.CultureInfo]::InvariantCulture).TotalSeconds }
function Mean-VolumeDb([string] $path) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = (& $script:ffmpeg -hide_banner -nostats -i $path -af volumedetect -f null NUL 2>&1 | Out-String)
    } finally { $ErrorActionPreference = $previousPreference }
    $match = [regex]::Match($output, 'mean_volume:\s*(-?\d+(?:\.\d+)?)\s*dB')
    if (-not $match.Success) { throw "Could not measure audible energy for $path." }
    [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
}
function Add-Case([Collections.Generic.List[object]] $cases, [string] $id, [string] $group, [string] $asset, [string] $reference, [System.Collections.IDictionary] $provenance) {
    if ([string]::IsNullOrWhiteSpace($reference)) { throw "Case $id has no reference text." }
    $file = Require-File $asset "Case $id audio is missing."
    $duration = [double](& $script:ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 $file)
    if ($duration -lt $MinDurationSeconds -or $duration -gt $MaxDurationSeconds) { throw "Case $id duration $duration is outside $MinDurationSeconds-$MaxDurationSeconds seconds." }
    $meanVolume = Mean-VolumeDb $file
    if ($meanVolume -lt -50.0) { throw "Case $id is too quiet for a reproducible speech benchmark ($meanVolume dB)." }
    $wordCount = @($reference.Normalize([Text.NormalizationForm]::FormKC).ToLowerInvariant() -split '[^\p{L}\p{N}]+' | Where-Object { $_ }).Count
    $cases.Add([ordered]@{ caseId = $id; languageGroup = $group; audioAsset = "audio/$id.mp3"; referenceText = $reference; referenceWordCount = $wordCount; audioSha256 = (Sha256 $file); durationMs = [long]($duration * 1000); meanVolumeDb = $meanVolume; provenance = $provenance })
}

$ffmpeg = Require-File $FfmpegPath 'Pass -FfmpegPath to a locally installed ffmpeg.exe.'
$ffprobe = Join-Path (Split-Path -Parent $ffmpeg) 'ffprobe.exe'
Require-File $ffprobe 'ffprobe.exe must be beside ffmpeg.exe.' | Out-Null
$script:ffprobe = $ffprobe
$english = Project-Path $EnglishArchive
if (-not (Test-Path -LiteralPath $english)) {
    if (-not $DownloadEnglish) { throw "LibriSpeech test-clean is missing. Re-run with -DownloadEnglish or place it at $english." }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $english) | Out-Null
    Invoke-WebRequest -Uri $EnglishUrl -OutFile $english
}
if ((Get-FileHash -LiteralPath $english -Algorithm MD5).Hash.ToLowerInvariant() -ne $EnglishMd5) { throw 'LibriSpeech MD5 does not match the official SLR12 checksum.' }
$indonesian = Require-File (Project-Path $IndonesianArchive) 'Indonesian Common Voice archive is missing.'
$homostoria = Require-File (Project-Path $HomostoriaArchive) 'Homostoria archive is missing.'
$root = Project-Path $OutputDirectory
$staging = "$root.staging"
$script:cases = [Collections.Generic.List[object]]::new()
if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$staging/audio", "$staging/work/en", "$staging/work/id", "$staging/work/mixed" | Out-Null

try {
    # LibriSpeech transcript lines are: SPEAKER-CHAPTER-UTTERANCE TEXT.
    & tar -xzf $english -C "$staging/work/en" | Out-Null
    $enRows = @(Get-ChildItem "$staging/work/en/LibriSpeech/test-clean" -Recurse -Filter '*.trans.txt' | ForEach-Object {
        $transcriptPath = $_.FullName
        Get-Content -LiteralPath $transcriptPath | ForEach-Object { $parts = $_ -split ' ', 2; if ($parts.Count -eq 2) { [pscustomobject]@{ id=$parts[0]; text=$parts[1]; speaker=($parts[0] -split '-')[0] } } }
    } | Where-Object { ($_.text -split '\s+').Count -ge 3 -and ($_.text -split '\s+').Count -le 24 } | Group-Object speaker | ForEach-Object { $_.Group | Select-Object -First 1 } | Sort-Object id | Select-Object -First $CasesPerLanguage)
    if ($enRows.Count -lt $CasesPerLanguage) { throw 'Not enough distinct-speaker LibriSpeech cases.' }
    $number = 1
    foreach ($row in $enRows) {
        $source = Get-ChildItem "$staging/work/en/LibriSpeech/test-clean" -Recurse -Filter "$($row.id).flac" | Select-Object -First 1
        $target = "$staging/audio/en-{0:d3}.mp3" -f $number
        & $ffmpeg -v error -y -i $source.FullName -vn -c:a libmp3lame -q:a 4 $target
        if ($LASTEXITCODE -ne 0) { throw "ffmpeg could not convert LibriSpeech case $($row.id)." }
        Add-Case $script:cases ("en-{0:d3}" -f $number) 'en' $target $row.text ([ordered]@{ dataset='LibriSpeech SLR12 test-clean'; license='CC-BY-4.0'; sourceUrl=$EnglishUrl; sourceUtterance=$row.id; selection='held-out distinct speaker' })
        $number++
    }

    $idTsv = & tar -xOf $indonesian 'cv-corpus-26.0-2026-06-12/id/test.tsv' | ConvertFrom-Csv -Delimiter "`t"
    $idRows = @($idTsv | Where-Object { $_.path -and $_.sentence -and [int]$_.up_votes -ge 2 -and ($_.sentence -split '\s+').Count -ge 3 -and ($_.sentence -split '\s+').Count -le 24 } | Group-Object client_id | ForEach-Object { $_.Group | Select-Object -First 1 } | Sort-Object path | Select-Object -First $CasesPerLanguage)
    if ($idRows.Count -lt $CasesPerLanguage) { throw 'Not enough distinct-speaker Indonesian Common Voice cases.' }
    $idList = Join-Path $staging 'id-files.txt'; $idRows | ForEach-Object { "cv-corpus-26.0-2026-06-12/id/clips/$($_.path)" } | Set-Content -LiteralPath $idList -Encoding ascii
    & tar -xzf $indonesian -C "$staging/work/id" -T $idList
    Remove-Item -LiteralPath $idList -Force
    $number = 1
    foreach ($row in $idRows) {
        $source = "$staging/work/id/cv-corpus-26.0-2026-06-12/id/clips/$($row.path)"
        $target = "$staging/audio/id-{0:d3}.mp3" -f $number
        Move-Item -LiteralPath $source -Destination $target
        Add-Case $script:cases ("id-{0:d3}" -f $number) 'id' $target $row.sentence ([ordered]@{ dataset='Mozilla Common Voice 26 Indonesian test'; license='CC0-1.0'; sourceClip=$row.path; selection='held-out distinct speaker' })
        $number++
    }

    & tar -xzf $homostoria -C "$staging/work/mixed" | Out-Null
    $mixedTsv = Get-ChildItem "$staging/work/mixed" -Recurse -Filter 'transcript_homostoria.tsv' | Select-Object -First 1
    $mixedRoot = $mixedTsv.Directory
    # Homostoria has no language labels. Require a multi-word English span inside
    # an Indonesian utterance and reject obvious title, quotation, and lyric rows.
    $englishToken = '(?:i|you|we|they|he|she|it|this|that|these|those|my|your|our|their|is|are|was|were|have|has|had|do|does|did|can|could|will|would|should|may|might|must|the|a|an|and|or|but|if|then|because|with|without|from|to|for|of|in|on|at|by|as|not|no|yes)'
    $englishSpan = "(?i)\b$englishToken\s+$englishToken\b"
    $indonesianMarker = '(?i)\b(?:aku|kamu|kita|kami|mereka|yang|dan|atau|tapi|karena|dengan|untuk|dari|ini|itu|jadi|kalau|nggak|tidak|bisa|sudah|udah|banget|gitu)\b'
    $allMixedRows = @(Import-Csv -LiteralPath $mixedTsv.FullName -Delimiter "`t" | Where-Object {
        $_.Text -and $_.Start -match '^\d{1,2}:\d{2}:\d{2}$' -and $_.End -match '^\d{1,2}:\d{2}:\d{2}$' -and
        ((Timestamp-Seconds $_.End) - (Timestamp-Seconds $_.Start)) -ge $MinDurationSeconds -and
        ((Timestamp-Seconds $_.End) - (Timestamp-Seconds $_.Start)) -le $MaxDurationSeconds
    })
    $mixedReview = @($allMixedRows | Where-Object { $_.Text -match $englishSpan } | ForEach-Object {
        $excluded = $_.Text -notmatch $indonesianMarker -or $_.Text.Contains('"') -or $_.Text -match '(?i)\b(?:lirik\p{L}*|lyrics|quote\p{L}*|judul lagu|lagunya)\b'
        [pscustomobject]@{ Included = (-not $excluded); Reason = $(if ($excluded) { 'quotation-title-or-no-Indonesian-context' } else { 'multi-word-English-span-with-Indonesian-context' }); AudioFile = $_.'Audio file'; Start = $_.Start; End = $_.End; Text = $_.Text }
    })
    # Keep the human review sheet beside, never inside, the APK asset pack.
    $reviewDirectory = Join-Path (Split-Path -Parent (Split-Path -Parent $root)) 'benchmark-review'
    New-Item -ItemType Directory -Force -Path $reviewDirectory | Out-Null
    $mixedReview | Export-Csv -LiteralPath (Join-Path $reviewDirectory 'MIXED_REVIEW.tsv') -Delimiter "`t" -NoTypeInformation -Encoding utf8
    $mixedRows = @($mixedReview | Where-Object Included | Select-Object -First $CasesPerLanguage)
    if ($mixedRows.Count -eq 0) { throw 'Homostoria contains no defensible code-switch cases under the recorded selection policy.' }
    $number = 1
    foreach ($row in $mixedRows) {
        $source = Get-ChildItem $mixedRoot -Recurse -Filter "$($row.AudioFile).mp3" | Select-Object -First 1
        if ($null -eq $source) { throw "Homostoria source $($row.AudioFile) is missing." }
        $target = "$staging/audio/en-id-{0:d3}.mp3" -f $number
        & $ffmpeg -v error -y -ss $row.Start -to $row.End -i $source.FullName -vn -c:a libmp3lame -q:a 4 $target
        if ($LASTEXITCODE -ne 0) { throw "ffmpeg could not extract Homostoria case $($row.AudioFile)." }
        Add-Case $script:cases ("en-id-{0:d3}" -f $number) 'en-id' $target $row.Text ([ordered]@{ dataset='Mozilla Data Collective Homostoria'; license='CC-BY-SA-4.0'; sourceEpisode=$row.AudioFile; start=$row.Start; end=$row.End; derivation='timestamped excerpt'; selectionPolicy=$row.Reason })
        $number++
    }

    # Only selected clips, manifest, and attribution may enter the benchmark APK.
    Remove-Item -LiteralPath "$staging/work" -Recurse -Force
    $manifest = [ordered]@{ schemaVersion=3; packId='stark-signal-offline-asr-v2'; purpose='Technical model evaluation only'; groups=[ordered]@{ en=[ordered]@{required=$CasesPerLanguage;actual=$CasesPerLanguage;complete=$true}; id=[ordered]@{required=$CasesPerLanguage;actual=$CasesPerLanguage;complete=$true}; 'en-id'=[ordered]@{required=$CasesPerLanguage;actual=$mixedRows.Count;complete=($mixedRows.Count -ge $CasesPerLanguage)} }; cases=$script:cases }
    $manifest | ConvertTo-Json -Depth 12 | Set-Content "$staging/manifest.json" -Encoding utf8
    @"
# Benchmark audio attribution

This pack is included only in the local Stark Signal benchmark APK.

- English: LibriSpeech SLR12 test-clean, CC BY 4.0, https://www.openslr.org/12/
- Indonesian: Mozilla Common Voice 26 Indonesian test split, CC0 1.0.
- Natural EN/ID: Mozilla Data Collective Homostoria, CC BY-SA 4.0.

The ordinary Stark Signal debug and release builds never include these assets.
"@ | Set-Content "$staging/ATTRIBUTION.md" -Encoding utf8
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
    Move-Item -LiteralPath $staging -Destination $root
    Write-Host "Created verified benchmark pack at $root with $($script:cases.Count) cases."
} finally { if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force } }
