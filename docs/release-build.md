# Signed release procedure

This project builds a Base-powered, arm64-only unsigned release APK. Keep the model, keystore, passwords, and signed artifacts outside source control.

## 1. Verify the local Base staging area

The required local files are:

```text
local-models/release-base/models/selected-model.bin
local-models/release-base/models/model-provenance.json
```

Run from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-StarkReleaseModelAssets.ps1 -AssetDirectory .\local-models\release-base
```

## 2. Create and protect the signing key

Create the key outside the repository. Replace `D:\Secure` with an encrypted location that is backed up offline.

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair `
  -keystore "D:\Secure\stark-signal-release.p12" `
  -storetype PKCS12 `
  -alias stark-signal `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

Store the keystore and its password separately. Make two encrypted offline backups before using the key for a public release.

## 3. Build and sign in Android Studio

For a manually installed GitHub APK, Android Studio is the recommended path. It invokes the same
release Gradle variant as the command line, keeps the key password out of the project, and signs
and aligns the APK for you.

1. Open the project in Android Studio and wait for Gradle sync to finish.
2. Confirm the Base staging check in step 1 has passed. The `release` build stops if the verified
   model or its provenance is missing.
3. Select **Build > Generate Signed Bundle / APK**.
4. Select **APK**, then select the `app` module.
5. Select the PKCS12 keystore created in step 2, enter its passwords, and select the
   `stark-signal` alias.
6. Select the `release` build variant. Leave the output outside the repository, for example
   `D:\Releases\StarkSignal`.
7. Complete the wizard. Use the resulting signed APK, not an unsigned file from `app\build`.

Android Studio signs and aligns the APK. Verify it and write its checksum before installing or
uploading it:

```powershell
$buildTools = "C:\Android\Sdk\build-tools\37.0.0"
$signed = "D:\Releases\StarkSignal\stark-signal-1.0.apk"
& "$buildTools\apksigner.bat" verify --verbose --print-certs $signed
$hash = Get-FileHash $signed -Algorithm SHA256
"$($hash.Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($signed))" | Set-Content -NoNewline "D:\Releases\StarkSignal\SHA256SUMS.txt"
Get-Content "D:\Releases\StarkSignal\SHA256SUMS.txt"
```

## 4. Publish the matching source

Before pushing, confirm that the legal documents identify Atan Forge, link to `https://github.com/atan-forge/stark-signal`, and state the unregistered Stark Signal name and icon policy. Do not add the model binary, keystore, `local.properties`, APKs, build output, recordings, benchmark data, or password files.

```powershell
Set-Location C:\Android\stark-audio
git init
git add .
git status
git commit -m "Stark Signal 1.0"
git branch -M main
git remote add origin https://github.com/atan-forge/stark-signal.git
git push -u origin main
git tag v1.0.0
git push origin v1.0.0
```

Review `git status` and `git diff --cached --stat` before committing. Create a GitHub Release from tag `v1.0.0`, then attach the signed APK and a plain-text SHA-256 file. Publish the source, GPLv3 licence, third-party notices, privacy notice, trademark guidance, model provenance, release notes, and signing-certificate fingerprint together.
