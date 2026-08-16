# DreamLog third-party notices and packaged assets

The runtime, production LiveKit wake models, and Silero boundary model below
are packaged with the arm64 DreamLog build. The transcription and enrichment
models are downloaded only after explicit user actions and installed in
app-private storage.

## sherpa-onnx 1.13.4 Android runtime

- Source: <https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4>
- Downloaded artifact SHA-256:
  `03F9C4DF965F21C71269365A7951A7F23B5696FDDD093FA318C80D65550AB780`
- Packaged arm64-only AAR SHA-256:
  `2E0E0C98D1D887EC7FCC55FF4E175151029595E263F97F59C0FD4FD493C67A43`
- License: Apache License 2.0

The packaged AAR is a mechanical copy of the official AAR with the
`armeabi-v7a`, `x86`, and `x86_64` JNI directories removed. DreamLog's initial
delivery profile targets the arm64 Pixel 8 Pro directly.

## ONNX Runtime 1.27.0 Java bridge

- Source: <https://github.com/microsoft/onnxruntime>
- License: MIT
- Packaged arm64 Java/JNI bridge SHA-256:
  `11A83E1DE84BB836146281AD0E9D04064B2F999248846AEDBAAF42013EE93FB0`

The small bridge AAR omits its own `libonnxruntime.so` and uses the existing
ONNX Runtime 1.27 core packaged with sherpa-onnx, avoiding two competing native
runtime copies.

## Locally trained DreamLog wake models

- Training code: <https://github.com/livekit/livekit-wakeword>
- Pinned revision: `95448A7559C453FCD87645BD67B247FFB45F85B0`
- License: Apache License 2.0
- `DreamLog` head SHA-256:
  `8DA21A475EDA39AA3B315D70238AE7EAE447447DAEC342A9C1404AF40B281F48`
- `Hey DreamLog` head SHA-256:
  `5866E2F201133545929A2A92EC9D4E6C71B673F3A6DEE9D065B9A3EAC23856C0`

The two classifier heads were trained locally from synthetic speech and noise.
The owner's wake-word recordings were reserved for evaluation and were not
included in training. Shared frontend model SHA-256 values are
`BA2B0E0F8B7B875369A2C89CB13360FF53BAC436F2895CCED9F479FA65EB176F`
and `70D164290C1D095D1D4EE149BC5E00543250A7316B59F31D056CFF7BD3075C1F`.

## GigaSpeech English offline transcription model

- Source:
  <https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-gigaspeech-2023-12-12>
- Immutable revision: `b609c835cd60c8ff0dd9b771f2b4edcfe2da943a`
- License declared by the model repository: Apache License 2.0
- Installed app-private files, source artifact names, and SHA-256 values:
  - `encoder.int8.onnx` from `encoder-epoch-30-avg-1.int8.onnx`:
    `D3EFF4B1BD747BD781A47966795988539227D05638524B0313F26D3A166962D7`
  - `decoder.onnx` from `decoder-epoch-30-avg-1.onnx`:
    `9610F32E7ADB66DD57FC31AF532652CDAA590BC3BBF7072A480B01C30592BDDA`
  - `joiner.int8.onnx` from `joiner-epoch-30-avg-1.int8.onnx`:
    `80160E45CCA71DD52F6B0A6D3D12BE18126F5308B2D4BA03F001300FEA377C64`
  - `tokens.txt`:
    `0EF7D736BF4DE3EF947292E4B119EF13F6808CD5F3AEC225A843A7135AC1C2CE`

The installed model totals 75,208,255 bytes. DreamLog verifies the exact byte
counts and hashes before atomically promoting a complete download. This model
is used only for on-device inference and is removable from the app.
The persisted aggregate manifest identity is
`16AAC2EAB0BDEE3AE320AF66AB041243FA663444D318EFB1DE8453E7E9E6DBB9`.

## LiteRT-LM 0.14.0 Android runtime

- Source: <https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.14.0>
- Packaged dependency: `com.google.ai.edge.litertlm:litertlm-android:0.14.0`
- License: Apache License 2.0

DreamLog uses the packaged arm64 runtime only for finite, app-open, on-device
dream enrichment. Native runtime logging is disabled while transcript content
is resident.

## Qwen3 4B Instruct 2507 enrichment model

- Source: <https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507>
- Immutable revision: `a7385088ed97778d7cf91a0b541fa1f95735f768`
- Installed artifact: `qwen3_4b_instruct_2507_mixed_int4.litertlm`
- Artifact size: 2,659,057,664 bytes
- SHA-256:
  `9E48B165836256F5344D9D044930607B9C47F6EF34E27F82E96881664F3BA2FD`
- License declared by the model repository: Apache License 2.0

The model is downloaded only after an explicit user action, verified before
atomic promotion into app-private storage, used only for local inference, and
removable from the app. Dream transcript content is never sent with the model
download.

## Silero VAD int8 model

- Source:
  <https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models>
- Packaged model SHA-256:
  `C36D490AFF5AB924CA6C7AEEC4D8F6BD3D22DB6FA17611B9C5B17EAE58AC3A20`
- Upstream project: <https://github.com/snakers4/silero-vad>
- Upstream license: MIT

## Project-supplied acknowledgement cue

The project owner supplied `chosen.mp3` for use as the local acknowledgement sound. It
contains no captured DreamLog audio.

- Source MP3 SHA-256:
  `19523B58B4E30F41B78D805E4C9090D41FF2D8E789FE4E36A6FA48A687B3E68C`
- Deployed `res/raw/m01_cue.wav` SHA-256:
  `A9729A864FAEB7635F21F354CEBC46F36A92E89549D03B4CC6E8829532ADB534`
- Mechanical conversion: FFmpeg downmix to mono, 48 kHz, uncompressed PCM16,
  with source metadata removed.

The repository records project-supplied provenance; this is not a third-party
software dependency and no separate public license is granted for the source
recording.

## Project-supplied launcher artwork

The project owner supplied the simplified DreamLog thought-cloud artwork. The
deployed adaptive-icon derivative preserves the complete centered mark, replaces
the dark navy field with black, and brightens the violet-to-cyan gradient for
clearer launcher-size contrast. A further 4 dp adaptive-foreground inset keeps
the smallest thought bubble inside the conservative circular keyline while
preserving a generous visible mark under squircle and rounded-square masks.

- Supplied PNG SHA-256:
  `40F607EE9E8F205714ACB2C590DA69BDEBE5D09612034BEBA909BE8FE8DC2C83`
- Deployed `res/drawable-nodpi/m01_launcher_art.png` SHA-256:
  `FC2D9CF1DBFE0A3866A33144BF0A8CE0A88C98CD2A603FC8453626203CF2EA91`

The full-color raster is used for the adaptive launcher icon and the public
README. Android's
foreground-service small icon remains a purpose-built monochrome vector because
the platform renders notification small icons from its alpha silhouette.
