# DrishtiAI Capture SDK - Android (Kotlin skeleton)

Gradle library module (`capture-sdk`) providing an on-device port of the
scoring logic in `sdk-core`. This is a skeleton: it compiles conceptually
against standard AndroidX/CameraX APIs and is meant to be dropped into an
Android Studio project as an included build or published as an internal
Maven artifact - it isn't a runnable app on its own.

## Structure

```
capture-sdk/
  src/main/java/com/drishtiai/capture/
    DrishtiCaptureSDK.kt       # public facade - init(), analyzeFrame(), checkForUpdates()
    model/QualityConfig.kt     # mirrors storage/quality_config.json
    model/QualityResult.kt     # score/decision/checks/guidance
    scoring/ImageUtils.kt      # grayscale/mean/stddev/laplacian-variance math
    scoring/ScoringEngine.kt   # weighted lenient scoring, ports sdk-core 1:1
    camera/CameraXAnalyzer.kt  # ImageAnalysis.Analyzer placeholder integration
    update/ConfigUpdateManager.kt  # fetches version.json/quality_config.json, applies updates
    storage/PersistentStore.kt # SharedPreferences-backed config persistence
```

## Why a Kotlin port instead of a TS bridge?

TypeScript can't run natively on Android without a JS engine (adds size and
latency), so `sdk-core`'s math is ported directly to Kotlin. Keep
`ScoringEngine.kt` numerically in sync with `sdk-core/src/scoring/*` when
tuning thresholds or weights - see comments in each file.

## Dependencies

- AndroidX CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`) - Apache 2.0, optional (only used by the camera integration point).
- `org.json` - ships with the Android platform, no extra dependency.
- No OkHttp/Retrofit/Gson - plain `HttpURLConnection` keeps the update manager dependency-free.

## Next steps to make this buildable in a real project

1. Include this module via `settings.gradle.kts` (`includeBuild` or copy into an existing multi-module Android project).
2. Wire `DrishtiCaptureSDK.init(context, backendBaseUrl)` at app startup.
3. Call `checkForUpdates()` on a schedule (e.g. `WorkManager` periodic job) in addition to app startup.
4. Feed `newCameraAnalyzer { result -> ... }` into your CameraX `ImageAnalysis` use case.
5. When a tiny TFLite object-presence model is ready, add `org.tensorflow:tensorflow-lite` and wire it into `ScoringEngine`'s `objectPresence` check (placeholder currently always passes).
