# Compatibility

| Area | Current scope | Other vehicles |
|---|---|---|
| Android | API 29+; Java 8 bytecode; SDK 34 compilation | Requires firmware allowing normal app execution and requested permissions |
| CPU ABI | arm64-v8a native adapter | Other ABIs need separate native builds and camera transport validation |
| DesaySV native camera | Independently implemented client of observed HAB/ION camera service; one Jaecoo 7 2026 test environment | Same brand/model/year does not guarantee the same protocol or camera IDs |
| Standard Camera2 | Portable adapter where the OS enumerates and authorizes camera devices | Not physically validated on other cars; AVM often is not exposed through Camera2 |
| GPU/video | OpenGL ES/EGL processing and a hardware MediaCodec encoder (Camera2 permits software fallback); silent video | Formats, dimensions, throughput and driver behavior are device dependent |
| Vehicle telemetry | Optional Android car APIs accessed without a mandatory android.car runtime; location can provide a fallback | Speed/ignition permissions or API availability can be absent. Unknown data must remain unknown |
| Storage | App-private internal clips or user-selected writable SAF tree | USB host role, mount format and persistent grants are controlled by the vehicle/Android |

Only the current DesaySV vehicle configuration has direct vehicle evidence. This is not a universal AVM driver or a promise of support for every Jaecoo/Chery/Android car. Camera2 devices can be ordinary Android cameras and can differ in quantity, naming, resolution and lens model from the four-camera DesaySV arrangement.

## Porting checklist

1. Start from unmodified vehicle settings. Identify Android/API/ABI and read-only camera enumeration.
2. Use the standard Camera2 adapter if those cameras are actually visible. A missing AVM camera cannot be made visible by changing an app setting.
3. For another proprietary transport, implement a separate source adapter from permitted interface evidence. Keep transport, pixel format, camera mapping and lens calibration separate from gallery/storage/UI.
4. Verify pixel format, stride, dimensions, image orientation, per-camera timestamps and source FPS. Confirm that no previous vehicle's device paths or channels are assumed correct.
5. Calibrate lens parameters for the target camera. FOV 80/100/120 is a viewing preference, not proof of calibration.
6. Use small recordings first. Measure source cadence, encoded cadence, dropped frames, bitrate, thermal state and file playback. Increase quality only after stable results.
7. Leave unsupported speed/gear/ignition values absent. Do not infer ignition from a fixed gear or treat stale data as live.
8. Verify power-state automation manually while parked before enabling unattended recording.

This package performs camera/media processing. It contains no ADB-unlock keys, date-code generator, controller firmware, vehicle-coding tool or instructions to change safety-system configuration.
