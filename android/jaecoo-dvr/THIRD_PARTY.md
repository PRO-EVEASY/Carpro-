# Source provenance and external components

## Included project-owned implementation

The Java application and `native/bridge.c`, `native/own/*.c` and `native/own/*.h` are the implementation maintained in this project. The native camera transport and pixel conversion were written for this project from observed protocol/ABI behavior. Project provenance records state that the previously extracted DSA native core was removed from the application source and is not an APK build input.

The source exporter permits only text source files from those application/native directories plus the manifest and distribution build/documentation files. It does not copy a reverse-engineering workspace, decompiled application, extracted library, vehicle diagnostic record or previous APK.

MIT in `LICENSE` covers only rights held in this project-owned code and documentation. It does not relicense a manufacturer interface, driver, Android system library, trademark, firmware, codec implementation, patent or third-party application. This inventory describes this source distribution; it is not a legal ownership audit of all external interfaces.

## Required but not redistributed

| Component | Role | Distribution |
|---|---|---|
| Android SDK/build tools | Android APIs, aapt, D8, zipalign, apksigner | Recipient installs under the SDK's terms; not copied into this package |
| Android NDK/Clang/CMake | Compile the native C implementation | Recipient installs toolchains and applicable notices; no precompiled adapter included |
| JDK | Java compiler and packaging tools | Recipient supplies their own JDK |
| Android Gradle Plugin / Gradle | Optional build frontend | Dependency coordinates only; no plugin JAR or wrapper JAR bundled |
| Android platform/EGL/OpenGL ES/MediaCodec | Runtime graphics and codec APIs | Provided by device firmware, not shipped here |
| DesaySV/HAB/ION driver and camera service | Native vehicle camera backend | Remain part of the vehicle firmware; no driver/service binary or copied proprietary header is bundled |
| Android car service | Optional vehicle telemetry | Provided by compatible firmware; accessed reflectively when available |

There is no required DSA APK, DSA shared library, Cockpit installer helper or hosted activation API in this source package. Android system/framework implementation licenses remain with their respective providers. Replacing a vehicle camera backend requires fresh compatibility work.

## Excluded private material

No signing keys, ADB executables, `.key`/`.flag` authorization files, serial/VIN lists, coordinate logs, calibration captures, recorded videos, app-data dumps or personal workstation paths are intentionally distributed. The exporter additionally writes a SHA-256 inventory and rejects binary files and obvious private identifiers in selected source text. Review the generated inventory and contents before publication.
