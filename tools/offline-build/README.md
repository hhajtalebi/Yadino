# Hermetic offline build (no Gradle / Maven access)

> The official way to build Yadino is **Gradle / Android Studio** (see `docs/BUILD.md`).
> This directory contains an alternative pipeline that was used to compile, test and
> package the app in an environment without access to Maven Central, Google Maven or
> services.gradle.org.

All inputs come from public GitHub mirrors of AOSP prebuilts and from upstream source
repositories pinned to exact tags (see `fetch-toolchain.sh`):

| Input | Source |
|---|---|
| JDK 17.0.4 | `platform/prebuilts/jdk/jdk17` |
| Kotlin 2.0.21 compiler, Compose compiler plugin, kapt | `platform/external/kotlinc` + `prebuilts/tools` (embeddable compiler) |
| android.jar (API 35), aapt2, zipalign, apksigner | `platform/prebuilts/sdk` |
| AndroidX (Compose, Room, WorkManager, DataStore, …) | `platform/prebuilts/sdk/current/androidx` |
| R8 8.11 | `platform/prebuilts/r8` |
| AndroidX Test (compile only) | `platform/prebuilts/misc` |
| kotlinx.coroutines 1.9.0, okio, kotlinpoet, javapoet, KSP API, auto-common, kotlinx.serialization 1.7.3, kotlin-metadata-jvm 2.0.21, JUnit | compiled from source by `build-deps.sh` |

Pipeline (`build.py`):

```
aapt2 compile/link → javac (R, BuildConfig) → kapt + Room processor → kotlinc + Compose
→ javac (Room generated code) → R8 (dex) → zip → zipalign → apksigner
```

Usage:

```bash
./fetch-toolchain.sh                   # ~1.2 GB into ~/.cache/yadino-toolchain (YADINO_TC)
./build-deps.sh                        # compiles libraries that are not prebuilt
python3 build.py test                  # JVM unit tests (calendar, domain, app)
python3 build.py apk                   # debug APK  -> build/offline/yadino-<v>-debug.apk
python3 build.py androidtest           # compiles instrumented tests (no device needed)
python3 build.py linkcheck             # binary compatibility check of the shrunk app
YADINO_KEYSTORE=… YADINO_KEYSTORE_PASSWORD=… python3 build.py release
```

`debug.keystore` is the standard Android debug key (password `android`).
