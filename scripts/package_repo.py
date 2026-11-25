#!/usr/bin/env python3
"""Build, sign and package the Mihon extension repository."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"

MODULES = [
    {
        "module": "unimanga",
        "pkg": "eu.kanade.tachiyomi.extension.ar.unimanga",
        "name": "UniManga",
        "base_url": "https://unimanga.net",
        "source_id": 5514574523778303112,
        "version": "1.6.4",
        "code": 4,
    },
    {
        "module": "mangarabi",
        "pkg": "eu.kanade.tachiyomi.extension.ar.mangarabi",
        "name": "MangaArabi",
        "base_url": "https://mangarabi.com",
        "source_id": 5119676554409942197,
        "version": "1.6.4",
        "code": 4,
    },
    {
        "module": "mangarabia",
        "pkg": "eu.kanade.tachiyomi.extension.ar.mangarabia",
        "name": "MangaArabia",
        "base_url": "https://mangarabia.com",
        "source_id": 8283840404796410502,
        "version": "1.6.4",
        "code": 4,
    },
    {
        "module": "mangacool",
        "pkg": "eu.kanade.tachiyomi.extension.ar.mangacool",
        "name": "MangaCool",
        "base_url": "https://mangacool.net",
        "source_id": 2075092417212105666,
        "version": "1.6.4",
        "code": 4,
    },
    {
        "module": "prochan",
        "pkg": "eu.kanade.tachiyomi.extension.ar.prochan",
        "name": "ProChan",
        "base_url": "https://procomic.pro",
        "source_id": 4021144253066869644,
        "version": "1.6.12",
        "code": 11,
    },
]


def read_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def find_sdk_tool(name: str) -> Path:
    local_properties = read_properties(ROOT / "local.properties")
    sdk = local_properties.get("sdk.dir", "").replace("\\:", ":").replace("\\", "/")
    candidates = []
    if sdk:
        candidates.append(Path(sdk))
    candidates.append(Path(r"D:\Android\Sdk"))

    for sdk_dir in candidates:
        build_tools = sdk_dir / "build-tools"
        if not build_tools.is_dir():
            continue
        for version in sorted(build_tools.iterdir(), reverse=True):
            tool = version / name
            if tool.exists():
                return tool
    raise SystemExit(f"Could not find {name} in the Android SDK")


def run(*args: str) -> str:
    command = list(args)
    if command[0].lower().endswith(".bat"):
        command = ["cmd.exe", "/c", *command]
    completed = subprocess.run(command, check=True, capture_output=True, text=True)
    return completed.stdout + completed.stderr


def main() -> int:
    keystore = read_properties(ROOT / "keystore.properties")
    keystore_file = ROOT / keystore["storeFile"]
    if not keystore_file.exists():
        raise SystemExit(f"Keystore not found: {keystore_file}")

    zipalign = find_sdk_tool("zipalign.exe")
    apksigner = find_sdk_tool("apksigner.bat")

    apk_dir = DIST / "apk"
    icon_dir = DIST / "icon"
    for directory in (apk_dir, icon_dir):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)

    entries = []
    fingerprint = ""

    temp_dir = DIST / ".tmp"
    temp_dir.mkdir(parents=True, exist_ok=True)

    for module in MODULES:
        release_dir = ROOT / "extensions" / module["module"] / "build" / "outputs" / "apk" / "release"
        apks = sorted(release_dir.glob("*.apk"))
        if not apks:
            raise SystemExit(f"No release APK found in {release_dir}")

        source_apk = next((apk for apk in apks if "unsigned" in apk.name), apks[0])
        aligned = temp_dir / f"{module['module']}-aligned.apk"
        signed = temp_dir / f"{module['module']}-signed.apk"

        run(str(zipalign), "-f", "-p", "4", str(source_apk), str(aligned))
        run(
            str(apksigner),
            "sign",
            "--ks", str(keystore_file),
            "--ks-pass", f"pass:{keystore['storePassword']}",
            "--key-pass", f"pass:{keystore['keyPassword']}",
            "--ks-key-alias", keystore["keyAlias"],
            "--out", str(signed),
            str(aligned),
        )

        verify = run(str(apksigner), "verify", "--print-certs", str(signed))
        match = re.search(r"SHA-256 digest:\s*([0-9a-fA-F:]+)", verify)
        if not match:
            raise SystemExit(f"Could not read certificate fingerprint from {signed}")
        fingerprint = match.group(1).replace(":", "").lower()

        apk_name = f"{module['pkg']}-v{module['version']}.apk"
        shutil.copy2(signed, apk_dir / apk_name)

        icon_source = ROOT / "artwork" / f"{module['module']}.png"
        if not icon_source.exists():
            raise SystemExit(f"Missing icon: {icon_source}")
        shutil.copy2(icon_source, icon_dir / f"{module['pkg']}.png")

        entries.append(
            {
                "name": f"Tachiyomi: {module['name']}",
                "pkg": module["pkg"],
                "apk": apk_name,
                "lang": "ar",
                "code": module["code"],
                "version": module["version"],
                "nsfw": 0,
                "sources": [
                    {
                        "id": module["source_id"],
                        "lang": "ar",
                        "name": module["name"],
                        "baseUrl": module["base_url"],
                    },
                ],
            },
        )
        print(f"Signed {apk_name}")

    (DIST / "index.min.json").write_text(
        json.dumps(entries, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    (DIST / "repo.json").write_text(
        json.dumps(
            {
                "index_v2": None,
                "meta": {
                    "name": "Bilalmesk Arabic Extensions",
                    "shortName": "Bilalmesk",
                    "website": "https://github.com/Bilalmesk/mihon-extensions",
                    "signingKeyFingerprint": fingerprint,
                },
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    shutil.rmtree(temp_dir, ignore_errors=True)
    print(f"Fingerprint: {fingerprint}")
    print(f"Repository files written to {DIST}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
