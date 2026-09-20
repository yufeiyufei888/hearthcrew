"""Create a reproducible, media-free, clearly unofficial library bundle; never install it."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PIN = "947f0064f3374adc0341e61687215ae32ea9765a"
MEDIA = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".ogg", ".wav", ".mp3", ".mp4", ".webm", ".ttf", ".otf"}
NOTICE = f"""HearthCrew execution library - UNOFFICIAL DERIVATIVE, NOT an official Numen release.
Original code copyright Dwinovo and contributors, upstream {PIN}.
Source: https://github.com/Dwinovo/minecraft-numen
Code: LGPL-3.0-only; only the public com.dwinovo.numen.api package has the MIT exception.
Changes: original artwork/audio/personas removed; display metadata relabeled; no bytecode changes.
Internal mod IDs and ABI versions retained only for linkage compatibility.
No endorsement or permission to use original branding or reserved assets is implied.
See the accompanying corresponding-source archive and reproduction instructions.
You may modify/rebuild/replace this library under its licenses; no signature lock is imposed.
"""

def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def excluded(name: str) -> bool:
    lower = name.lower()
    if lower.endswith(".class"):
        return False
    return (Path(lower).suffix in MEDIA or lower.endswith(".png.mcmeta")
            or "/textures/" in lower or "/sounds/" in lower
            or lower.endswith("/sounds.json") or "/assets/" in "/" + lower and "/persona/" in lower)

def archive(entries: dict[str, bytes]) -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            target.writestr(info, data)
    return out.getvalue()

def relabel(data: bytes, part: str) -> bytes:
    text = data.decode("utf-8")
    text = re.sub(r'^logoFile\s*=.*\n?', '', text, flags=re.M)
    text = re.sub(r'^displayName\s*=.*$', f'displayName = "HearthCrew execution library ({part}, unofficial)"', text, flags=re.M)
    text = re.sub(r"description\s*=\s*'''.*?'''", "description = '''Unofficial media-free execution library derived from LGPL code by Dwinovo. See HEARTHCREW-DERIVATIVE-NOTICE.txt.'''", text, flags=re.S)
    return text.encode("utf-8")

def transform(data: bytes, part: str, licenses: dict[str, bytes], removed: list[str]) -> bytes:
    entries = {}
    with zipfile.ZipFile(io.BytesIO(data)) as source:
        for item in source.infolist():
            name = item.filename
            if item.is_dir():
                continue
            if excluded(name):
                removed.append(f"{part}:{name}")
                continue
            content = source.read(item)
            if name.endswith(".jar"):
                content = transform(content, "embedded-api", licenses, removed)
            elif name == "META-INF/neoforge.mods.toml":
                content = relabel(content, part)
            entries[name] = content
    entries.update({"META-INF/licenses/" + key: value for key, value in licenses.items()})
    entries["HEARTHCREW-DERIVATIVE-NOTICE.txt"] = NOTICE.encode()
    return archive(entries)

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, help="Fresh output directory; existing outputs are never overwritten")
    args = parser.parse_args()
    output = Path(args.output).resolve()
    lock = json.loads((ROOT / "backend-lab/upstream-lock.json").read_text("utf-8"))
    source_zip = ROOT / ".runtime/backend-v03/numen-947f0064.zip"
    original = source_zip.read_bytes()
    if lock["commit"] != PIN or digest(original).upper() != lock["sourceArchiveSha256"].upper():
        raise ValueError("Pinned source archive does not match lock")
    upstream = ROOT / ".runtime/backend-v03/numen-947f0064"
    licenses = {name: (upstream / name).read_bytes() for name in ("LICENSE", "LICENSE-API", "LICENSE-ASSETS")}
    licenses["GPL-3.0.txt"] = (ROOT / "backend-lab/licenses/GPL-3.0.txt").read_bytes()
    # Do all input validation before creating the bundle.
    binaries = {}
    expected = {"core": "b353121d4da974b2eb6b26a6337470d16158a8668467ec845f03a9776f8ca621",
                "api": "0ef725fce2586bc31310f43b2e5c227771b8e8ab7dc59e836a4c989ee1e65bb5"}
    for part in expected:
        name = "numen" if part == "core" else "numen_api"
        data = (upstream / part / f"neoforge/build/libs/{name}-neoforge-1.21.1-0.1.3-dev.jar").read_bytes()
        if digest(data) != expected[part]:
            raise ValueError(f"Unexpected {part} build; independently verify new build before changing pinned hash")
        binaries[part] = data
    output.mkdir(parents=True, exist_ok=False)
    removed: list[str] = []
    outputs = {}
    for part, data in binaries.items():
        transformed = transform(data, part, licenses, removed)
        name = f"hearthcrew-execution-library-{part}-947f0064-mediafree.jar"
        (output / name).write_bytes(transformed)
        outputs[part] = {"file": name, "sha256": digest(transformed), "inputSha256": digest(data)}
    with zipfile.ZipFile(io.BytesIO(original)) as source:
        entries = {e.filename: source.read(e) for e in source.infolist() if not e.is_dir() and not excluded(e.filename)}
    entries["HEARTHCREW-DERIVATIVE-NOTICE.txt"] = NOTICE.encode()
    entries["GPL-3.0.txt"] = licenses["GPL-3.0.txt"]
    code = archive(entries)
    (output / "corresponding-source-mediafree.zip").write_bytes(code)
    (output / Path(__file__).name).write_bytes(Path(__file__).read_bytes())
    (output / "NOTICE.txt").write_text(NOTICE, "utf-8")
    (output / "REPRODUCE.txt").write_text(
        "Build source with Java 21 and Gradle 9.2.0: :core:neoforge:jar :api:neoforge:jar -Pneoforge_version=21.1.249.\n"
        "The source archive excludes reserved media/personas; compiled classes correspond to the pinned source.\n"
        "The included transform functions remove media and relabel metadata without changing classes.\n"
        "The script main validates the original locally verified build hashes; a media-free rebuild has different archive hashes.\n"
        "To relink, rebuild the library and replace this separate library JAR; HearthCrew does not verify a restrictive runtime signature.\n"
        "Only the core bundle (with embedded API) is a runtime dependency. The separate API file is for compilation.\n"
        "Keep this notice, licenses, corresponding source and the application source/build instructions with any delivery.\n", "utf-8")
    manifest = {"schema": 1, "commit": PIN, "unofficial": True, "installed": False,
                "sourceSha256": digest(code), "outputs": outputs, "removed": sorted(set(removed))}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", "utf-8")
    print(json.dumps({"output": str(output), "outputs": outputs, "removedCount": len(set(removed)), "installed": False}))

if __name__ == "__main__":
    main()
