"""Record SHA-256 + size of built native libraries in native/lock.json "artifacts" (see hash.sh)."""
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
NAMES = ("libmokuhyo_native.dylib", "libmokuhyo_native.so", "mokuhyo_native.dll")


def main() -> None:
    lock_path = HERE / "lock.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    artifacts = lock.setdefault("artifacts", {})
    for platform_dir in sorted((HERE / "build").glob("*-*")):
        if not platform_dir.is_dir() or platform_dir.name.split("-")[0] not in ("macos", "linux", "windows"):
            continue
        for variant_dir in sorted(p for p in platform_dir.iterdir() if p.is_dir()):
            for name in NAMES:
                lib = variant_dir / name
                if lib.is_file():
                    digest = hashlib.sha256(lib.read_bytes()).hexdigest()
                    key = f"{platform_dir.name}/{variant_dir.name}"
                    artifacts[key] = {"file": name, "sha256": digest, "bytes": lib.stat().st_size}
                    print(f"{key}: {name} {lib.stat().st_size} bytes sha256={digest}")
    lock["artifacts"] = dict(sorted(artifacts.items()))
    lock_path.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
