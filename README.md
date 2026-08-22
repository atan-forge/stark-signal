# Stark Signal

Stark Signal is a local-first Android recorder and media preparation utility. It records compact audio, inspects local audio and video, removes video tracks when requested, prepares provider-safe audio, and provides an engine boundary for offline English and Indonesian transcription.

Project home: [github.com/atan-forge/stark-signal](https://github.com/atan-forge/stark-signal)

## Privacy contract

- No `INTERNET` permission
- No account, analytics, ads, or remote processing
- Private-vault storage by default
- No automatic Android backup or device transfer
- Explicit save and share actions
- Visible foreground notification during background recording

## Current implementation

The Kotlin application includes the OLED Compose interface, Room vault, DataStore settings, foreground recording service, Media3 inspection, playback and audio-only preparation, provider profiles, transcript export formats, optional system app lock, and privacy/legal surfaces.

Release builds use one verified local Base multilingual Whisper model. The model is bundled only from an ignored local staging directory after its SHA-256, byte count, licence, source URL, and pinned whisper.cpp revision have been verified. Debug builds remain model-free.

## Build

Requirements:

- Android Studio with API 37 SDK
- JDK supported by Android Gradle Plugin 9.3.1
- NDK r28+ and CMake when building the native transcription engine

Run the normal debug checks:

```powershell
$env:GRADLE_USER_HOME = "$PWD\.gradle"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

For a Base-powered release, stage the model at `local-models/release-base/models/` and run the release verifier. The model binary is deliberately excluded from source control; see [docs/transcription-model-provenance.md](docs/transcription-model-provenance.md).

The current managed Windows environment may block Maven-delivered `aapt2.exe` through Application Control. Allow the declared Android build tool or use Android Studio's approved SDK copy.

## Distribution

The initial distribution target is GitHub Releases. Official releases must be signed only with the project owner's protected signing key and accompanied by checksums, provenance, an SBOM, and release notes. No publishing automation is enabled by default.

Follow the complete local signing and GitHub publishing procedure in [docs/release-build.md](docs/release-build.md).

## License and identity

Copyright (C) 2026 Atan Forge. The application source is distributed under GNU GPL version 3. Third-party components retain their own licenses. The Stark Signal name, icon, official repository identity, signing keys, and release designation are governed separately by [TRADEMARK.md](TRADEMARK.md).
