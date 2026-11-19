# Bible Audio Producer

Record and review Greek-form audio using the
[MACULA Greek](https://github.com/Clear-Bible/macula-greek/) and
[MACULA Hebrew](https://github.com/Clear-Bible/macula-hebrew/) linguistic datasets.
Greek New Testament support is included first; Hebrew is not yet bundled.
The complete Greek New Testament recording plan is bundled and available offline on first
launch. The first screen lists projects; a new project with no name is assigned a name such
as **Project001**. New projects start with John 3:16 to try a word order and record a
verse; existing projects keep their previous behavior. Select a book, tap the chapter
button to choose a chapter, then choose **Show verses** to navigate its verses. Back
returns to the previous screen. Tap a word to
inspect its Macula metadata and English gloss, then record or play that Greek form.
Hold a word until it lifts, drag it into place, and release to drop it. Reset restores the
original Greek order. Disable or restore a form from its word details.
Closing the verse returns to the chapter list or recording cards. Select a book and chapter side by side;
cards are sorted by frequency within that chapter. Open **Settings** from the gear
icon to set the recording language, import plans or audio, export archives, or manage data.
Imports in a different language are rejected to prevent mixing projects. Plans and
recordings remain in private storage; export them before uninstalling.

In a verse, select a word-order mode or drag individual words, then choose **Add verse
recording**. Record the whole verse in that order. The app suggests boundaries at quiet
points; select each word, adjust the start and end with the sliders or typed seconds,
preview it, and save it or keep its existing clip. Suggestions are approximate and must
be reviewed. Disabled words are omitted; restore them in word details before recording
if needed. Verse clips are saved as lossless WAV audio, including very short selections.
Each saved clip is keyed by Greek surface form, so repeated forms share a
recording: replacing any existing clip requires confirmation. Back discards the unsaved
verse take but keeps clips already saved. Once every word is reviewed, **Finish verse**
saves the order and advances from John 3:16 to John 1:1, then through the following
verses. The selected preset is used for subsequent verses unless they already have a
saved order; a custom order is specific to its verse.

The chapter verse list and verse text show pronunciation above each Greek word and
English and Chinese glosses below where available. Recorded forms are marked
green and disabled forms grey. The chapter list can play or save each verse, and its header
plays or stops the full chapter. For natural neural speech with Classical/Erasmian pronunciation, the app can map
polytonic Greek to IPA and embed it in Azure Speech SSML. Configure an Azure Speech
subscription and region in Settings for IPA-based neural synthesis; the speech service
receives the Greek text and generated IPA. The subscription key is encrypted with Android
Keystore. Without a key, choose an installed Greek or Italian Android TTS voice in Settings.
Greek voices speak the original text directly, including the Samsung Greek Classic voice
when it is exposed by the active system TTS engine. On Samsung, set the engine that owns
the downloaded voice as the preferred engine in Android's **Text-to-speech output**
settings, then reopen the app and choose the voice in **Settings**. CI installs eSpeak NG
and checks offline Greek synthesis on an Android emulator; it does not test Samsung's engine.
Italian voices use a pronunciation
respelling instead. This fallback is approximate (Android TTS
does not standardize IPA or SSML support), depends on installed voice data, and the voice
provider may process speech locally or over its network. The IPA transcription models
Classical vowel quality, explicit macrons, breathing, diphthongs, and stress; it does not
reproduce the pitch contour of ancient Greek pitch accent.

Inspired by BTT Recorder's take-review workflow, replacing an existing recording requires
reviewing the new take first. The current take remains intact until the replacement is
accepted, including if the app is interrupted during review.

In verse word details, tap Record to open the take dialog immediately. The live new
waveform appears above the current take, if there is one. Stop recording to preview the
takes with their play/stop buttons, then select a waveform to keep that take and close
the dialog. Closing a playback window stops its audio.

On recording cards, hold the red record button and release to save; drag far away or
cancel the gesture to discard the take. A live waveform shows microphone activity. When
using an accessibility service or keyboard, activate the button once to start and again
to stop. After granting microphone permission, start the recording again. The play
button changes to stop during playback. Disabled forms are skipped in playback and shown
in grey; recordings and exported archives are keyed by the Greek surface form rather than
shared by the lemma.
When a word-list recording is saved, the next unrecorded card is brought to the same
position in the list.

Verse playback uses the recordings available on the device; missing words are skipped.
Playback normalizes levels, trims silence, and crossfades joins. Enable neural smoothing
globally in Settings, choose a per-verse opt-out, and select standard or strong blending.
Choose **Save verse audio** from a verse to export its recorded words in the selected
order, with normalized levels and crossfaded joins. Choose lossless WAV, compatible
AAC (M4A), or compact Opus (Ogg, Android 10 or later). All formats also support neural
smoothing. The app reports how many missing words were skipped. Computer voice is not
yet available.

On Android 7.0 or later, **Save with neural smoothing** optionally blends an on-device
SpeechT5 HiFi-GAN reconstruction into verses up to 30 seconds long. Choose the standard
35% blend or the stronger 65% blend. To enable it,
download the [pinned ONNX model](https://huggingface.co/Xenova/speecht5_hifigan/resolve/cf980c3610d7b7f20919960031066ef7905737bd/onnx/model.onnx)
(55,432,026 bytes) and choose **Import neural model** in Settings. The app verifies
its SHA-256 before use. Processing happens on the device; recordings are never uploaded.
The model may alter voice quality, so compare the result with the unsmoothed export.
The SpeechT5 model and ONNX Runtime are provided under the MIT license by Microsoft;
the model is distributed separately and ONNX Runtime license text is in app settings.

## Acknowledgements

Thank you to [MaculaScripta](https://github.com/danzuep/MaculaScripta) for the audio
workbench and plan generator, and to [BTT Recorder](https://github.com/Bible-Translation-Tools/BTT-Recorder)
for inspiration from its recording and take-review workflows. This project also builds on
the upstream [Story Producer](https://github.com/sillsdev/StoryProducer) repository.

## Development

Android 6.0 (API 23) or later is required. Build and test with the Gradle-pinned JDK 17,
the Android SDK, Node.js, curl, and network access to GitHub. Gradle uses JDK 17 for its
build daemon even when the shell's default Java is newer:

```sh
./gradlew :core:jvmTest :app:testDebugUnitTest :app:assembleDebug
```

`core` contains platform-independent models and workbench rules in Kotlin Multiplatform
`commonMain`. The Android app owns file storage, JSON, audio recording, playback, and UI.
The Android application ID is `io.github.danzuep.bibleaudioproducer`; it is a new app,
not an upgrade of Story Producer.

Every build, including CI and releases, generates the bundled assets from pinned source
revisions, including [MACULA Greek](https://github.com/Clear-Bible/macula-greek/tree/8423afe47b9e8f24b7772e808af45c7159a6fe7e).
The plan generator and its revision are specified in `scripts/generate-macula-assets.sh`.
The generator checks the complete 27-book, 4,917-card plan rather than accepting a sample.
Gradle reuses generated assets until its inputs change or the build directory is cleaned.
An existing imported plan remains in place; clearing saved data restores the bundled plan.

The plan and verse data are adapted from the
[MACULA Greek Linguistic Datasets](https://github.com/Clear-Bible/macula-greek/),
© 2022–2024 Biblica, Inc., and the
[SBL Greek New Testament](https://github.com/LogosBible/SBLGNT/),
© 2010 Society of Biblical Literature and Logos Bible Software, both licensed
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Additional third-party
attribution and license text are available in the app's settings.

## Releases

Pull requests merged into `main` with a `major:`, `minor:`, or `patch:` title prefix
create a version tag and dispatch the APK release workflow. The `release` environment
can use `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` for
stable release signing. If all four are absent, Gradle signs with a temporary debug key:
the APK names include `temporary-key`, and tagged releases are marked as prereleases.
These APKs cannot upgrade installations signed with a different key. Configure all four
secrets together for a production release; partial configuration fails the workflow.
Manual runs without a version tag use the `dry-run` environment.
When no version tags exist, the next `major:` PR starts at `v1.0.0`.
