[CmdletBinding()]
param([Parameter(Mandatory)] [string] $ManifestDirectory)
$ErrorActionPreference = 'Stop'
$manifest = Get-ChildItem -LiteralPath $ManifestDirectory -Recurse -File -Filter AndroidManifest.xml | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $manifest) { throw 'The merged release manifest was not produced.' }
[xml]$xml = Get-Content -LiteralPath $manifest.FullName -Raw
$android = 'http://schemas.android.com/apk/res/android'
$forbidden = @(
    'android.permission.INTERNET', 'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.MANAGE_EXTERNAL_STORAGE',
    'android.permission.CAMERA', 'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION', 'android.permission.READ_CONTACTS',
    'com.google.android.gms.permission.AD_ID'
)
$permissions = @($xml.manifest.'uses-permission' | ForEach-Object { $_.GetAttribute('name', $android) })
$present = @($forbidden | Where-Object { $_ -in $permissions })
if ($present.Count -gt 0) { throw "Forbidden merged-manifest permissions: $($present -join ', ')" }
$components = @($xml.manifest.application.activity) + @($xml.manifest.application.service) + @($xml.manifest.application.receiver) + @($xml.manifest.application.provider)
$exported = @($components | Where-Object { $_.GetAttribute('exported', $android) -eq 'true' })
$allowed = @('com.atan.starkaudio.MainActivity', 'androidx.profileinstaller.ProfileInstallReceiver')
$unexpected = @($exported | ForEach-Object { $_.GetAttribute('name', $android) } | Where-Object { $_ -notin $allowed })
if ($unexpected.Count -gt 0) { throw "Unexpected exported components: $($unexpected -join ', ')" }
$profile = $exported | Where-Object { $_.GetAttribute('name', $android) -eq 'androidx.profileinstaller.ProfileInstallReceiver' }
if ($null -ne $profile -and $profile.GetAttribute('permission', $android) -ne 'android.permission.DUMP') { throw 'ProfileInstallReceiver is missing its privileged DUMP permission guard.' }
Write-Host "Merged manifest policy passed: $($manifest.FullName)"
