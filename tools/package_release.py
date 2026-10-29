"""Package a signed release with source, checksums and public update metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--out", type=Path, default=ROOT / "dist")
parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
args = parser.parse_args()
out = args.out.resolve()
out.mkdir(parents=True, exist_ok=True)
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
version = re.search(r"versionName\s+'([^']+)'", gradle)[1]
code = int(re.search(r"versionCode\s+(\d+)", gradle)[1])
minimum = int(re.search(r"minSdk\s+(\d+)", gradle)[1])
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    raise SystemExit("Release version must have three numeric parts")
base = "LightNovelLibrary-" + version
apk = out / (base + ".apk")
if not apk.is_file():
    raise SystemExit("Build the signed release APK first: " + str(apk))
if args.sdk:
    tool = Path(args.sdk).resolve() / "build-tools/34.0.0" / ("aapt2.exe" if os.name == "nt" else "aapt2")
    data = subprocess.check_output([str(tool), "dump", "badging", str(apk)], text=True, encoding="utf-8")
    if "versionName='" + version + "'" not in data or "versionCode='" + str(code) + "'" not in data or "application-debuggable" in data:
        raise SystemExit("APK version or release flags do not match")
with zipfile.ZipFile(apk) as z:
    if not {"classes.dex", "AndroidManifest.xml"}.issubset(z.namelist()):
        raise SystemExit("APK is incomplete")
raw = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode("utf-8")
files = sorted(set(p for p in raw.split("\0") if p))
for name in files:
    p = Path(name)
    if any(s in {".git", ".signing", "build", "dist", "work", "outputs", "__pycache__"} for s in p.parts) or p.suffix.lower() in {".jks", ".keystore", ".apk", ".pyc"} or p.name in {"TASK.md", "local.properties", "reader-signing.json"} or p.name.startswith(".env"):
        raise SystemExit("Refusing to package local/private file: " + name)
source = out / (base + "-source.zip")
with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as z:
    for name in files:
        z.write(ROOT / name, "LightNovelLibrary/" + name)
with zipfile.ZipFile(source) as z:
    for name in files:
        if z.read("LightNovelLibrary/" + name) != (ROOT / name).read_bytes():
            raise SystemExit("Source archive mismatch")
changes = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
section = re.search(r"^## " + re.escape(version) + r"\s.*?\n(.*?)(?=^## |\Z)", changes, re.M | re.S)
if not section:
    raise SystemExit("Missing current version changelog")
notes = "\n".join(line for line in section[1].splitlines() if line.startswith("- "))
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
metadata = {"schema": 1, "version": version, "versionCode": code, "minSdk": minimum, "notes": notes,
            "apk": {"name": apk.name, "url": "https://github.com/XiaHusuper/light-novel-library/releases/download/v" + version + "/" + apk.name,
                    "size": apk.stat().st_size, "sha256": sha}}
manifest = out / "LightNovelLibrary-update.json"
manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
tick = chr(96)
description = ("面向 [Wenku8（轻小说文库）](https://www.wenku8.net/) 的 Android 阅读器。\n\n本次更新：\n\n" + notes +
               "\n\n下载下方 " + tick + apk.name + tick + " 安装，可覆盖旧版。0.7.1、0.8.0 用户需先手动升级一次，此后支持启动更新提醒。\n\n" +
               "[完整更新日志](https://github.com/XiaHusuper/light-novel-library/blob/main/CHANGELOG.md)\n")
release_notes = out / (base + "-release-notes.md")
release_notes.write_text("# 轻小说文库 " + version + "\n\n" + description, encoding="utf-8")
artifacts = [apk, source, release_notes, manifest]
checksums = out / (base + "-SHA256SUMS.txt")
checksums.write_text("".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in artifacts), encoding="ascii")
artifacts.append(checksums)
report = {"version": version, "versionCode": code, "sourceFiles": len(files), "artifacts": [
    {"name": p.name, "bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in artifacts]}
(out / (base + "-package-report.json")).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report))
