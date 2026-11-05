#!/usr/bin/env python3
"""Allowlisted text-only export. Never reads original build outputs or vehicle evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

DOCS = ("KULLANIM.md", "TESTING.md", "README.md", "BUILDING.md", "COMPATIBILITY.md", "THIRD_PARTY.md", "LICENSE", ".gitignore", "build.ps1", "CMakeLists.txt", "build.gradle", "settings.gradle", "gradle.properties", "package_source.py")
FORBIDDEN_SUFFIXES = {".so", ".apk", ".aab", ".dex", ".jar", ".jks", ".keystore", ".key", ".flag", ".exe", ".dll", ".mp4", ".mkv", ".jpg", ".png", ".bin", ".log", ".pdf"}
PRIVATE_PATTERNS = (
    ("personal Windows path", re.compile(r"[A-Za-z]:[\\/]+Users[\\/]+[^\\/\s]+", re.I)),
    ("private key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("VIN-like identifier", re.compile(r"\bL[A-HJ-NPR-Z0-9]{16}\b")),
    ("literal location", re.compile(r"(?:latitude|longitude|lat|lon)\s*[:=]\s*[-+]?\d{2}\.\d{4,}", re.I)),
)


def safe_relative(path, root):
    if path.is_symlink():
        raise ValueError("Symlinks are not exported: " + path.name)
    relative = path.relative_to(root)
    path.resolve().relative_to(root.resolve())
    if any(part.startswith(".") for part in relative.parts):
        raise ValueError("Hidden source path rejected: " + relative.as_posix())
    return relative.as_posix()


def checked_text(data, name):
    if Path(name).suffix.lower() in FORBIDDEN_SUFFIXES or b"\x00" in data:
        raise ValueError("Binary or forbidden input: " + name)
    text = data.decode("utf-8-sig", errors="strict")
    if any(ord(char) < 32 and char not in "\r\n\t" for char in text):
        raise ValueError("Non-text control byte: " + name)
    # This exporter necessarily contains its own pattern rules, not private values.
    if name != "package_source.py":
        for description, pattern in PRIVATE_PATTERNS:
            if pattern.search(text):
                raise ValueError(description + " found in selected source: " + name)
    return text.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")


def collect(project, templates):
    entries = {}
    for prefix, suffixes in (("src", {".java"}), ("native", {".c", ".h"})):
        folder = project / prefix
        if not folder.is_dir():
            raise ValueError("Missing application source directory: " + prefix)
        for file in sorted(folder.rglob("*")):
            if file.is_file() and file.suffix.lower() in suffixes:
                name = safe_relative(file, project)
                if any(token in name.lower() for token in ("decompil", "eski-dsa", "third_party", "third-party", "reference", "installer")):
                    raise ValueError("Unexpected third-party/installer source path: " + name)
                entries[name] = checked_text(file.read_bytes(), name)
    manifest = project / "AndroidManifest.xml"
    safe_relative(manifest, project)
    entries["AndroidManifest.xml"] = checked_text(manifest.read_bytes(), "AndroidManifest.xml")
    for name in DOCS:
        entries[name] = checked_text((templates / name).read_bytes(), name)
    for file in sorted((templates/"tests").glob("*")):
        if file.is_file() and file.suffix in {".java",".ps1"}:
            name="tests/"+file.name
            entries[name]=checked_text(file.read_bytes(),name)
    required = ["native/bridge.c", "native/own/hab_transport.c", "native/own/own_camera.c", "native/own/own_camera_jni.c", "native/own/own_mosaic.c", "native/own/own_mosaic_jni.c", "native/own/own_lens.c", "native/own/own_lens_jni.c", "src/dev/local/jaecoorecorder/MainActivity.java"]
    for name in required:
        if name not in entries:
            raise ValueError("Required build source missing: " + name)
    entries["SOURCE-MANIFEST.json"] = (json.dumps({
        "schema": 1,
        "scope": "allowlisted project-owned text source; no compiled libraries or APK",
        "files": [{"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()} for name, data in sorted(entries.items())],
    }, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return entries


def verify_archive(path):
    with zipfile.ZipFile(path, "r") as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entries")
        for name in names:
            if name.startswith(("/", "\\")) or ".." in Path(name).parts or "\\" in name:
                raise ValueError("Unsafe ZIP path: " + name)
            checked_text(archive.read(name), name)
        manifest = json.loads(archive.read("SOURCE-MANIFEST.json"))
        indexed = {row["path"] for row in manifest["files"]}
        if indexed != set(names) - {"SOURCE-MANIFEST.json"}:
            raise ValueError("Manifest/ZIP file inventory mismatch")
        for row in manifest["files"]:
            data = archive.read(row["path"])
            if hashlib.sha256(data).hexdigest() != row["sha256"] or len(data) != row["bytes"]:
                raise ValueError("Source hash mismatch: " + row["path"])
    return len(names)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, help="Application directory containing src/native/AndroidManifest.xml")
    parser.add_argument("--output", type=Path, help="New ZIP path, must not already exist")
    parser.add_argument("--check", action="store_true", help="Validate selected source without creating an archive")
    parser.add_argument("--verify", type=Path, help="Verify a previously generated source ZIP")
    args = parser.parse_args()
    if args.verify:
        count = verify_archive(args.verify)
        print(json.dumps({"verified": True, "files": count, "sha256": hashlib.sha256(args.verify.read_bytes()).hexdigest()}))
        return
    if not args.project:
        parser.error("--project is required unless --verify is used")
    project = args.project.resolve(strict=True)
    entries = collect(project, Path(__file__).resolve().parent)
    if args.check:
        print(json.dumps({"checked": True, "files": len(entries), "bytes": sum(map(len, entries.values())), "archiveCreated": False}))
        return
    if not args.output:
        parser.error("--output is required unless --check is used")
    output = args.output.absolute()
    if output.exists():
        raise ValueError("Refusing to overwrite an existing archive")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation prevents replacing another file. A failed validation leaves the new file for inspection.
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            item = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            item.create_system = 3
            item.external_attr = 0o100644 << 16
            archive.writestr(item, data)
    count = verify_archive(output)
    print(json.dumps({"created": str(output), "files": count, "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, UnicodeError, zipfile.BadZipFile) as error:
        print("Export rejected: " + str(error), file=sys.stderr)
        sys.exit(1)
