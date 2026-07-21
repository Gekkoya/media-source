import os
import sys
from pathlib import Path
import shutil

repo_apk_dir = Path("repo/apk")
artifact_dir = Path(sys.argv[1]) if len(sys.argv) == 2 else Path(os.environ.get("APK_ARTIFACTS_DIR", Path.home() / "apk-artifacts"))
shutil.rmtree(repo_apk_dir, ignore_errors=True)
repo_apk_dir.mkdir(parents=True, exist_ok=True)

for apk in artifact_dir.glob("**/*.apk"):
    apk_name = apk.name.replace("-release.apk", ".apk").replace("-debug.apk", ".apk")
    shutil.move(apk, repo_apk_dir / apk_name)
