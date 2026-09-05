<h1 align="center"><img src="docs/images/dreamlog-mark.svg" width="70" alt="DreamLog logo"> DreamLog</h1>

<p align="center"><strong>Hands-free, screen-off dream capture with local transcription on Android.</strong></p>

<p align="center">
  <a href="https://github.com/wivy1/dreamlog/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/wivy1/dreamlog?display_name=tag&sort=semver"></a>
  <img alt="Android 12 or newer" src="https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white">
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
</p>

Record dreams without sitting up, looking at a screen, or typing. DreamLog transcribes your recordings on the phone and can organize them into separate dreams.

## How it works

1. Open **Settings** to download the speech model and optional enrichment model.

   **Stop every other microphone/listening app (e.g. SnoreLab) before starting a DreamLog night.**

2. Tap **Start night**. DreamLog checks microphone access and alert volume. A warning tells you when the wake alert may use connected audio, such as Bluetooth headphones; you can still start.
3. When you wake, say **"DreamLog"** or **"Hey DreamLog"**, wait for the alert, then speak naturally. Narrate in any order, using phrases such as "the first dream" or "the next dream."
4. Stop speaking and go back to sleep. Recording ends after 10 seconds of silence, and DreamLog returns to listening.
5. In the morning, tap **End night**. Transcription runs on the phone, including with the screen off. Tap **Enrich** to organize the narration into dreams; keep DreamLog open until it finishes.
6. Open a night to review its dreams and sources. Share or save selected nights as **TXT**, **JSON**, or **CSV**.

## Screenshots

<table>
  <tr>
    <td width="25%" valign="top">
      <p align="center"><strong>Ready to start</strong></p>
      <a href="docs/images/01-ready-to-start.png"><img src="docs/images/01-ready-to-start.png" alt="DreamLog ready to start with dream history"></a>
    </td>
    <td width="25%" valign="top">
      <p align="center"><strong>Local model setup</strong></p>
      <a href="docs/images/02-local-models.png"><img src="docs/images/02-local-models.png" alt="DreamLog local transcription and enrichment model setup"></a>
    </td>
    <td width="25%" valign="top">
      <p align="center"><strong>Enriching a dream</strong></p>
      <a href="docs/images/03-enriching-dreams.png"><img src="docs/images/03-enriching-dreams.png" alt="DreamLog organizing dream narrations locally"></a>
    </td>
    <td width="25%" valign="top">
      <p align="center"><strong>Reviewing a dream</strong></p>
      <a href="docs/images/04-enriched-dream.png"><img src="docs/images/04-enriched-dream.png" alt="A processed dream in DreamLog"></a>
    </td>
  </tr>
</table>

## Private and local

Dream audio, raw transcripts, and organized dreams stay in DreamLog's private local storage. Transcription and optional enrichment run on the phone. Network access is used only for explicit model downloads, and DreamLog never uploads dream content.

## Requirements

Requires Android 12 or newer on an arm64 device. One-time downloads are about 663 MB for transcription and 2.66 GB for optional enrichment.

[Download the signed APK from the latest GitHub release.](https://github.com/wivy1/dreamlog/releases/latest)

## Build from source

Install Java 17 and an Android SDK containing API 37, then run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Release signing credentials are intentionally excluded from Git. Device integration tests use the isolated `deviceTest` package; do not run `connectedDebugAndroidTest` against an installation containing real data.

Bug reports and focused pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

Third-party runtimes, models, assets, licenses, and exact artifact provenance are documented in [app/THIRD_PARTY_NOTICES.md](app/THIRD_PARTY_NOTICES.md).

## License

MIT
