# Contributing to DreamLog

Bug reports and focused pull requests are welcome.

## Bug reports

Search existing issues first. Include the DreamLog version, Android version, device model, expected behavior, observed behavior, and clear reproduction steps.

## Development

Install Java 17 and an Android SDK containing API 37, then run:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Keep changes focused, explain user-visible behavior, and add or update tests when practical.
