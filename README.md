# Bible Audio Producer

Record and review lemma audio for [MaculaScripta](https://github.com/danzuep/MaculaScripta).
Import a `recording-plan.json`, choose a book, and record each prompt. Enter the recording
language and export to create MaculaScripta-compatible ZIP archives. Plans and recordings
remain in the app's private storage; export them before uninstalling.

## Development

Android 6.0 (API 23) or later is required. Build and test with JDK 17 and the Android SDK:

```sh
./gradlew :core:jvmTest :app:testDebugUnitTest :app:assembleDebug
```

`core` contains platform-independent models and workbench rules in Kotlin Multiplatform
`commonMain`. The Android app owns file storage, JSON, audio recording, playback, and UI.
The Android application ID is `io.github.danzuep.bibleaudioproducer`; it is a new app,
not an upgrade of Story Producer.

## Releases

Pull requests merged into `develop` with a `major:`, `minor:`, or `patch:` title prefix
create a version tag and dispatch the APK release workflow. The `release` environment
can use `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` for
stable release signing. If all four are absent, Gradle signs with a temporary debug key:
the APK names include `temporary-key`, and tagged releases are marked as prereleases.
These APKs cannot upgrade installations signed with a different key. Configure all four
secrets together for a production release; partial configuration fails the workflow.
Manual runs without a version tag use the `dry-run` environment.
