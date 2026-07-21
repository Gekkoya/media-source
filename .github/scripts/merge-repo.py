import json
import html
import shutil
import sys
from pathlib import Path

remote_repo = Path.cwd()
local_repo = remote_repo.parent / sys.argv[2]
try:
    to_delete: list[str] = json.loads(sys.argv[1])
except json.JSONDecodeError:
    to_delete = [module for module in sys.argv[1].split(",") if module]

for module in to_delete:
    apk_pattern = f"symera-{module.replace('.', '-')}-v*.apk"
    icon_pattern = f"org.symera.mediasource.{module}.png"
    for file in (remote_repo / "apk").glob(apk_pattern):
        file.unlink(missing_ok=True)
    for file in (remote_repo / "icon").glob(icon_pattern):
        file.unlink(missing_ok=True)

shutil.copytree(local_repo / "apk", remote_repo / "apk", dirs_exist_ok=True)
shutil.copytree(local_repo / "icon", remote_repo / "icon", dirs_exist_ok=True)
shutil.copy2(local_repo / "synapse.json", remote_repo / "synapse.json")

remote_index_path = remote_repo / "index.json"
remote_index = json.loads(remote_index_path.read_text("utf-8")) if remote_index_path.is_file() else []
local_index = json.loads((local_repo / "index.json").read_text("utf-8"))


def normalize_artifact_path(item: dict) -> dict:
    normalized = dict(item)
    apk = normalized["apk"]
    normalized["apk"] = apk if apk.startswith("apk/") else f"apk/{apk}"
    return normalized

deleted_suffixes = tuple(f".{module}" for module in to_delete)
merged = [normalize_artifact_path(item) for item in remote_index if not item["pkg"].endswith(deleted_suffixes)]
merged.extend(normalize_artifact_path(item) for item in local_index)
merged.sort(key=lambda item: item["pkg"])

(remote_repo / "index.json").write_text(json.dumps(merged, ensure_ascii=False, indent=2), "utf-8")
(remote_repo / "index.min.json").write_text(json.dumps(merged, ensure_ascii=False, separators=(",", ":")), "utf-8")
with (remote_repo / "index.html").open("w", encoding="utf-8") as file:
    file.write("<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\"><title>Symera extensions</title></head><body><pre>\n")
    for item in merged:
        apk = html.escape(item["apk"])
        name = html.escape(item["name"])
        file.write(f"<a href=\"{apk}\">{name}</a>\n")
    file.write("</pre></body></html>\n")
