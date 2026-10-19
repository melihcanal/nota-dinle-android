# NotaDinle

Android app that reads a photographed or imported score and plays the detected melody with local acoustic piano or guitar samples.

## On-device processing

The score image stays on the phone. Image selection uses Android's document picker, camera capture uses the system camera app, and PDF pages are rasterized with Android `PdfRenderer`. There is no network permission, cloud recognition API, or per-scan service cost.

The `omr-core` module is plain Kotlin/JVM and has no Android dependencies. It receives an 8-bit grayscale raster and performs deskew attempts, staff-line detection, treble-staff pitch mapping, notehead/stem/flag/dot analysis, and beat-length estimation locally. The first version targets clear, printed, single-voice treble-clef scores; handwritten notation, key signatures/accidentals, multiple voices, and chords need further work and user correction.

## Structure

```text
app/
  data/score       Android image decoding, EXIF handling, PDF page rendering
  data/omr         Local OMR repository adapter
  data/audio       Offline sample loading and playback
  domain/          Score/instrument models, repository contracts, use case
  presentation/    Compose screen and screen-level ViewModel
omr-core/          Platform-independent image and score recognition code
```

The UI follows unidirectional data flow: `HomeViewModel` exposes a `StateFlow<HomeUiState>` and receives actions from Compose. `AppContainer` provides the small number of app dependencies without introducing a DI framework.

## Build

Open this folder in Android Studio and run the `app` configuration, or build from PowerShell:

```powershell
./gradlew.bat :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
