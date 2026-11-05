# Jaecoo DVR — source distribution

Android camera preview and silent loop recorder, with lens correction, GPU image processing, protected clips, local gallery and optional vehicle/GPS telemetry.

This archive contains source code. It contains no APK, compiled native library, DEX/JAR, signing key, installer helper, vehicle dump or recorded video. Every recipient builds their own `libcameraadapter.so` and APK.

- [Build and tests](BUILDING.md)
- [Vehicle compatibility](COMPATIBILITY.md)
- [Source and platform provenance](THIRD_PARTY.md)
- [MIT license for project-owned code](LICENSE)

The current vehicle-tested backend targets one DesaySV/HAB/ION configuration. A car is supported only when its camera transport, permissions and image format match a backend. The presence of Android alone does not expose AVM cameras.

MIT permits copying, modification and redistribution. This public source package does not enforce private licensing or prevent forks.

## Produce a new source archive

With Python 3.9+ and the source root as the current directory:

```text
python package_source.py --project . --check
python package_source.py --project . --output ../jaecoo-dvr-source.zip
python package_source.py --verify ../jaecoo-dvr-source.zip
```

The output path must be new. The exporter normalizes text to UTF-8/LF, uses stable ZIP timestamps, and includes `SOURCE-MANIFEST.json` with every source file's SHA-256. It selects its own build/documentation files, Java under `src`, C/H under `native`, and the manifest. It does not recursively archive the working directory. Do not replace this process by zipping an entire developer workspace: build outputs and personal evidence can exist outside the allowlist.
