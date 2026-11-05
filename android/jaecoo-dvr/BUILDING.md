# Building from source

## Requirements

- Windows PowerShell 5.1 or PowerShell 7 for the standalone build, or Android Studio/Gradle for the optional Gradle build.
- JDK 17 or newer; set `JAVA_HOME` to the JDK directory containing `bin/javac`.
- Android SDK platform 34, Android SDK Build Tools 34.0.0 or newer, and an installed Android NDK supporting API 29. The tested native recipe used NDK 28.2.13676358 with API 29.
- For Gradle: Gradle 8.11.1, Android Gradle Plugin 8.9.1, JDK 17, SDK 34 and CMake 3.22.1. No Gradle wrapper JAR is distributed.
- Python 3.9+ only if producing or verifying the source archive; Python is not a runtime application dependency.

Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) and `JAVA_HOME` in your shell. Optionally set `ANDROID_NDK_HOME` to a particular installed NDK. Paths are discovered from these variables; no original developer account or desktop path is needed.

## Standalone Windows build

From the extracted source directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

The script compiles the native adapter from the included C files, compiles Java against SDK 34, runs D8, packages one arm64-v8a native library, aligns the APK and signs it with a debug key generated locally in `.local-build`. The output is `output/jaecoo-recorder-debug.apk`. No installation or permission grant is performed by the script. A debug signature is for development; independently arrange your own release signing before distributing an APK.

The script does not download build tools. Install the SDK/NDK first. During dependency installation your IDE or SDK manager may use the network. The app does not require a cloud service for video processing, storage or license activation.

## Optional Android Studio / Gradle

Open this directory as a Gradle project, set the SDK location using your own `local.properties` or environment, and select your installed NDK through `ANDROID_NDK_HOME` or a local Gradle configuration if needed. Then:

```text
gradle assembleDebug
```

The root project applies the Android application plugin and builds `cameraadapter` through CMake from source. Gradle dependencies may require an initial online download; once cached, an offline build can be attempted with `gradle --offline assembleDebug`. Toolchain versions affect byte-for-byte APK output; source hashes are reproducible, but identical APK bytes are not promised across JDK/NDK/signing installations.

## Reproducible local validation

1. Build. Check that `lib/arm64-v8a/libcameraadapter.so` is the only packaged native library. No DSA or vendor binary is an input to the recipe.
2. On an authorized device install the locally built APK with your own Android tools. Initial camera/location and USB directory selection remain interactive. Do not import a previous vehicle's settings.
3. With preview and recording stopped, trigger the isolated storage suite from a host shell where `adb` is already available:

```text
adb shell am start -n dev.local.jaecoorecorder/.MainActivity --ez test_storage true
adb shell run-as dev.local.jaecoorecorder cat files/storage-selftest.json
```

The suite uses UUID directories inside the app cache, in-memory preferences and tiny synthetic files. It tests indexed quota deletion and lock/active-file protection, not MP4 validity. A successful report has `success: true` and `failed: 0`. Stop recording before this test because the recorder permits one writer per process.

4. Park the target vehicle, start preview and confirm the selected source and frame timestamps. Run a short local recording, stop it, play it, and inspect actual frame rate, codec warnings, image corruption and thermal state before increasing duration. Use the UI test/diagnostic controls included in the particular source version. Do not assume configured FPS equals measured FPS.
5. Verify gallery playback, protected clip deletion rejection, unlock/delete, segment rotation, quota exhaustion and source disconnect behavior. Automatic start/stop should be enabled only after that device's ignition/power source is confirmed. Do not simulate car control packets to test it.

The device tests above must be repeated for each distinct firmware/hardware combination. Host compilation and synthetic storage tests alone do not validate a vehicle camera backend.
