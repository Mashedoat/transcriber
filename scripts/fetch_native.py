"""Restore the pinned official Android runtime; Python 3.9+, no extra packages."""
from pathlib import Path
import argparse
import hashlib
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
DIGEST = "2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44"
LIBRARIES = ("libonnxruntime.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so", "libsherpa-onnx-jni.so")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already downloaded official tar.bz2")
    args = parser.parse_args()
    cache = ROOT / "scripts" / ".cache"
    cache.mkdir(exist_ok=True)
    archive = args.archive or cache / "sherpa-onnx-v1.13.8-android.tar.bz2"
    if not archive.exists():
        if args.archive:
            parser.error("The specified archive does not exist")
        print("Downloading the pinned upstream runtime (~46 MB)...", flush=True)
        partial = archive.with_suffix(".part")
        urllib.request.urlretrieve(URL, partial)
        partial.replace(archive)
    digest = hashlib.sha256()
    with archive.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != DIGEST:
        raise SystemExit("Archive checksum mismatch. Remove the cached archive and download the official release again.")
    dest = ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
    dest.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as package:
        for name in LIBRARIES:
            entries = [entry for entry in package.getmembers()
                       if entry.isfile() and Path(entry.name).name == name
                       and "arm64-v8a" in Path(entry.name).parts]
            if len(entries) != 1:
                raise SystemExit(f"Expected one arm64-v8a/{name}, found {len(entries)}")
            # Never extract archive-supplied paths, symlinks, or other members.
            source = package.extractfile(entries[0])
            if source is None:
                raise SystemExit(f"Cannot read {name}")
            partial = dest / (name + ".part")
            with source, partial.open("wb") as target:
                shutil.copyfileobj(source, target)
            partial.replace(dest / name)
            print(f"Restored arm64-v8a/{name}")
    print("Native runtime restored. Read DISTRIBUTION.md and THIRD_PARTY_NOTICES.md before redistributing the GPLv3 APK.")

if __name__ == "__main__":
    main()
