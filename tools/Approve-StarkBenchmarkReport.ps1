[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $ReportPath,
    [Parameter(Mandatory)] [string] $ReferenceDeviceName,
    [switch] $EndurancePassed,
    [switch] $RecoveryPassed,
    [switch] $Page16kPassed
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$resolved = Resolve-Path -LiteralPath $ReportPath
$report = Get-Content -LiteralPath $resolved -Raw | ConvertFrom-Json
$reasons = [Collections.Generic.List[string]]::new()

if ([int]$report.schemaVersion -lt 3) { $reasons.Add('Report schema predates decoded-duration, reliability, and native-alignment evidence and is ineligible.') }
if (-not $report.benchmarkPackComplete) { $reasons.Add('The benchmark pack is incomplete or has not been reviewed.') }
if ($report.abi -ne 'arm64-v8a') { $reasons.Add('ABI is not arm64-v8a.') }
if ([int64]$report.totalRamBytes -lt 4GB) { $reasons.Add('Device reports less than 4 GB RAM.') }
if ([int64]$report.peakRssBytes -ge 900MB) { $reasons.Add('Peak RSS is at or above 900 MB.') }

$cases = @($report.cases)
if ($cases.Count -eq 0) { $reasons.Add('Report has no case metrics.') }
else {
    $rtf = @($cases | ForEach-Object {
        if ([int64]$_.audioDurationMs -le 0) { [double]::PositiveInfinity }
        else { ([double]$_.decodeMs + [double]$_.inferenceMs) / [double]$_.audioDurationMs }
    } | Sort-Object)
    $p90 = $rtf[[math]::Max(0, [math]::Ceiling($rtf.Count * 0.9) - 1)]
    if ($p90 -gt 1.0) { $reasons.Add("p90 real-time factor is $p90, above 1.0.") }

    @{ en = 0.15; id = 0.20; 'en-id' = 0.30 }.GetEnumerator() | ForEach-Object {
        $key = $_.Key
        $limit = $_.Value
        $group = @($cases | Where-Object languageGroup -eq $key)
        if ($group.Count -eq 0) { $reasons.Add("No $key cases were reported.") }
        else {
            $words = [int64](($group | Measure-Object referenceWordCount -Sum).Sum)
            $edits = [int64](($group | Measure-Object editDistance -Sum).Sum)
            if ($words -le 0) { $reasons.Add("$key reference word counts are missing.") }
            elseif (($edits / [double]$words) -gt $limit) { $reasons.Add("$key corpus WER exceeds $([int]($limit * 100))%.") }
        }
    }
    if ((@($cases | Where-Object catastrophic).Count / [double]$cases.Count) -ge 0.005) { $reasons.Add('Catastrophic-error rate is at or above 0.5%.') }
    if (@($cases | Where-Object { $null -ne $_.errorCode -and $_.errorCode -ne '' }).Count -gt 0) { $reasons.Add('One or more cases reported an engine or decoder error.') }
}

$enduranceOk = $EndurancePassed -or $report.endurance60MinPassed
$recoveryOk = $RecoveryPassed -or ($report.cancellationPassed -and $report.recoveryPassed -and $report.repeatedLoadUnloadPassed)
$pageOk = $Page16kPassed -or ($report.page16KbCompatible -and [int64]$report.nativeLoadAlignmentBytes -ge 16384)
if (-not $enduranceOk) { $reasons.Add('60-minute endurance evidence was not supplied.') }
if (-not $recoveryOk) { $reasons.Add('Cancellation, recovery, or repeated-load evidence was not supplied.') }
if (-not $pageOk) { $reasons.Add('16 KB native-page evidence was not supplied.') }

$approval = [ordered]@{
    schemaVersion = 3
    reviewedAtUtc = [DateTime]::UtcNow.ToString('o')
    referenceDevice = $ReferenceDeviceName
    candidateId = $report.modelId
    modelSha256 = $report.modelSha256
    engineVersion = $report.engineVersion
    approved = ($reasons.Count -eq 0)
    reasons = @($reasons)
    reportSha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
}
$output = Join-Path (Split-Path -Parent $resolved) "stark-signal-$($report.modelId)-approval.json"
$approval | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $output -Encoding utf8
Write-Host "Approval record: $output"
if (-not $approval.approved) {
    Write-Host 'NOT APPROVED:'
    $reasons | ForEach-Object { Write-Host "- $_" }
    exit 2
}
Write-Host 'APPROVED: review and explicitly configure this model for an ordinary release build.'
