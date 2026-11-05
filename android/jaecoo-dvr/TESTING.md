# Validation scope

- Vehicle evidence before portable-backend/player changes: four actual HAB cameras, GPU corrected FOV100, AVC1920×1440@12Mbit/s,60sec,1501/1501frames decoded,zero reported drops. A2560×1920@20Mbit/s15sec test decoded376/376frames at approximately25fps. Foreground/background recording and5sec muxer rotation passed. These measurements are not a minimum-FPS guarantee on other hardware.
- Storage:18 isolated Android checks passed. Active/locked clips were preserved; quota eviction touched only isolated indexed test files.
- Power policy:24 standalone Java assertions pass. Android emulator interactive wake→automatic record→sleep→graceful stop→wake restart also passed. This is not a physical vehicle ignition off/on validation.
- Playback:real vehicle1920×1440MP4 opened in the new TextureView/MediaPlayer player on Android emulator. Pause held position; seek and resume advanced frames. Final player is not yet physically rechecked on the disconnected car.
- Camera2:Android API36emulator source1280×960AE15fps recorded76/76validframes over5.067sec using softwareAVC. This proves a generic adapter path, not another vehicle's AVM compatibility. The emulator used SwiftShader; its preview throughput is not a vehicle GPU benchmark.
- Preview:the old10fps cap and relative scheduling gate were replaced. Default target25fps, independently configurable and measured. Final25fps preview must still be validated on the target vehicle after reconnection.

Run `tests/run-policy-tests.ps1` for pure policy assertions. `StorageSelfTest` runs only in a stopped recorder, using an isolated cache context. Runtime acceptance on a new vehicle requires real camera and recorded-file tests before enabling auto start. Build validation proves compilation, not hardware compatibility.
