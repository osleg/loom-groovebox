#!/usr/bin/env python3
"""Package the complete matching build source, never caches, keys or old APKs."""
from pathlib import Path
import hashlib
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
destination = Path(sys.argv[1] if len(sys.argv) > 1 else root / "build/loom-cafa-source.zip")
destination.parent.mkdir(parents=True, exist_ok=True)
excluded_dirs = {".git", ".agent", ".agents", ".codex", ".gradle", ".idea", ".cxx", ".externalNativeBuild", "build", "Releases", "release", "__pycache__"}
excluded_files = {"local.properties", ".DS_Store"}
excluded_suffixes = {".keystore", ".jks", ".apk", ".idsig", ".hprof", ".class", ".pyc"}
files = []
for path in sorted(root.rglob("*")):
    relative = path.relative_to(root)
    if not path.is_file() or any(part in excluded_dirs for part in relative.parts):
        continue
    if path.name in excluded_files or path.suffix in excluded_suffixes:
        continue
    if path.name.startswith("build_log") or path.name in {"app_build_plain.txt", "build_output.txt"}:
        continue
    files.append((relative, path))
manifest = []
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for relative, path in files:
        data = path.read_bytes()
        manifest.append(hashlib.sha256(data).hexdigest() + "  " + relative.as_posix())
        # Fixed metadata makes source packaging deterministic for identical bytes.
        info = zipfile.ZipInfo("loom-cafa-source/" + relative.as_posix(), date_time=(2026, 11, 13, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o100755 if path.stat().st_mode & 0o111 else 0o100644) << 16
        archive.writestr(info, data)
    info = zipfile.ZipInfo("loom-cafa-source/SOURCE_MANIFEST.sha256", date_time=(2026, 11, 13, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(info, "\n".join(manifest) + "\n")
with zipfile.ZipFile(destination) as archive:
    assert archive.testzip() is None
    for line in archive.read("loom-cafa-source/SOURCE_MANIFEST.sha256").decode().splitlines():
        digest, relative = line.split("  ", 1)
        assert hashlib.sha256(archive.read("loom-cafa-source/" + relative)).hexdigest() == digest
print(f"{destination}: {len(files)} files, {destination.stat().st_size} bytes, SHA256 {hashlib.sha256(destination.read_bytes()).hexdigest()}")
