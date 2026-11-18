# Bible Audio Producer

Record and review lemma audio for [MaculaScripta](https://github.com/danzuep/MaculaScripta).
The complete Greek New Testament recording plan is bundled and available offline on first
launch. Enter the recording language, choose a book, and record its prompts. Use **Listen**
to review verses, record their words, arrange phrases for the target language, and mark
words as unspoken. Export recordings and grammar as MaculaScripta-compatible ZIP archives,
or import earlier archives without replacing existing recordings. Imports in a different
language are rejected to prevent mixing projects. Plans and recordings remain in private
storage; export them before uninstalling.

Verse playback uses the recordings available on the device; missing words are skipped.
The web workbench's optional computer voice, neural smoothing, and whole-verse audio
download are not yet available in the Android app.

## Development

Android 6.0 (API 23) or later is required. Build and test with JDK 17, the Android SDK,
Node.js, curl, and network access to GitHub:

```sh
./gradlew :core:jvmTest :app:testDebugUnitTest :app:assembleDebug
```

`core` contains platform-independent models and workbench rules in Kotlin Multiplatform
`commonMain`. The Android app owns file storage, JSON, audio recording, playback, and UI.
The Android application ID is `io.github.danzuep.bibleaudioproducer`; it is a new app,
not an upgrade of Story Producer.

Every build, including CI and releases, generates the bundled assets from pinned commits
of [MaculaScripta](https://github.com/danzuep/MaculaScripta/tree/dcfd3c024b3a00b51fcabc7e154129958500c1f9)
and [MACULA Greek](https://github.com/Clear-Bible/macula-greek/tree/8423afe47b9e8f24b7772e808af45c7159a6fe7e).
The generator checks the complete 27-book, 4,917-card plan rather than accepting a sample.
Gradle reuses generated assets until its inputs change or the build directory is cleaned.
An existing imported plan remains in place; clearing saved data restores the bundled plan.

The plan and verse data are adapted from the
[MACULA Greek Linguistic Datasets](https://github.com/Clear-Bible/macula-greek/),
© 2022–2024 Biblica, Inc., and the
[SBL Greek New Testament](https://github.com/LogosBible/SBLGNT/),
© 2010 Society of Biblical Literature and Logos Bible Software, both licensed
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The plan and grammar
algorithm are adapted from [MaculaScripta](https://github.com/danzuep/MaculaScripta/),
licensed under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Releases

Pull requests merged into `main` with a `major:`, `minor:`, or `patch:` title prefix
create a version tag and dispatch the APK release workflow. The `release` environment
can use `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` for
stable release signing. If all four are absent, Gradle signs with a temporary debug key:
the APK names include `temporary-key`, and tagged releases are marked as prereleases.
These APKs cannot upgrade installations signed with a different key. Configure all four
secrets together for a production release; partial configuration fails the workflow.
Manual runs without a version tag use the `dry-run` environment.
