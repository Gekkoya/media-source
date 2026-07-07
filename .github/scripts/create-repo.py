import html
import json
import os
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

PACKAGE_REGEX = re.compile(r"package: name='([^']+)'.*versionCode='([^']+)'.*versionName='([^']+)'")
LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
ICON_REGEX = re.compile(r"^application-icon-\d+:'([^']+)'", re.MULTILINE)
META_REGEX = re.compile(r"name='(?P<name>symera\.extension\.[^']+)' value='(?P<value>[^']*)'")
APK_LANGUAGE_REGEX = re.compile(r"^symera-([^-]+)-")

android_home = Path(os.environ["ANDROID_HOME"])
build_tools = sorted((android_home / "build-tools").iterdir())[-1]
aapt = build_tools / "aapt"

repo_dir = Path("repo")
apk_dir = repo_dir / "apk"
icon_dir = repo_dir / "icon"
icon_dir.mkdir(parents=True, exist_ok=True)

index = []

for apk in sorted(apk_dir.glob("*.apk")):
    badging = subprocess.check_output([aapt, "dump", "--include-meta-data", "badging", apk]).decode()
    package_line = next(line for line in badging.splitlines() if line.startswith("package: "))
    package_name, version_code, version_name = PACKAGE_REGEX.search(package_line).groups()
    metadata = {match.group("name"): match.group("value") for match in META_REGEX.finditer(badging)}

    icon_match = ICON_REGEX.search(badging)
    icon_name = f"{package_name}.png"
    if icon_match:
        with ZipFile(apk) as archive, archive.open(icon_match.group(1)) as source, (icon_dir / icon_name).open("wb") as target:
            target.write(source.read())

    language = APK_LANGUAGE_REGEX.search(apk.name)
    index.append(
        {
            "name": LABEL_REGEX.search(badging).group(1),
            "pkg": package_name,
            "apk": apk.name,
            "lang": language.group(1) if language else package_name.split(".")[-2],
            "code": int(version_code),
            "version": version_name,
            "nsfw": metadata.get("symera.extension.nsfw", "false") in {"1", "true", "True"},
            "sourceSdk": int(metadata.get("symera.extension.sdk", "0") or 0),
            "extClass": metadata.get("symera.extension.class", ""),
            "icon": f"icon/{icon_name}" if icon_match else None,
            "sources": [],
        }
    )

index.sort(key=lambda item: item["pkg"])
with (repo_dir / "index.json").open("w", encoding="utf-8") as file:
    json.dump(index, file, ensure_ascii=False, indent=2)
with (repo_dir / "index.min.json").open("w", encoding="utf-8") as file:
    json.dump(index, file, ensure_ascii=False, separators=(",", ":"))
with (repo_dir / "index.html").open("w", encoding="utf-8") as file:
    file.write("<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\"><title>Symera extensions</title></head><body><pre>\n")
    for item in index:
        apk = html.escape(item["apk"])
        name = html.escape(item["name"])
        file.write(f"<a href=\"apk/{apk}\">{name}</a>\n")
    file.write("</pre></body></html>\n")
