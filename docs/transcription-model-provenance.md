# Transcription Model Provenance

Status: Base multilingual model selected for the owner-controlled release build.

The release build verifies the locally staged model before packaging. The binary remains outside source control; the provenance record and staging verifier make the package reproducible without placing model data in the source repository.

Current verified record:

- model: `base-multilingual`, GGML F16
- SHA-256: `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`
- source: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin`
- licence: MIT
- engine: whisper.cpp 1.8.6

Record before bundling:

- whisper.cpp commit and release
- model name and source
- model license
- quantization
- exact SHA-256
- NDK version
- ABIs
- compiler and linker flags
- 16 KB page verification
- English, Indonesian, and mixed WER
- catastrophic-error rate
- peak RSS and p90 real-time factor on the 4 GB reference device
