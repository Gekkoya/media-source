#!/usr/bin/env python3

import re
import subprocess
import sys
from pathlib import Path

EXT_VERSION = re.compile(r"(extVersionCode\s*=\s*)(\d+)")
OVERRIDE_VERSION = re.compile(r"(overrideVersionCode\s*=\s*)(\d+)")
BASE_VERSION = re.compile(r"(baseVersionCode\s*=\s*)(\d+)")
LIB_FROM_PATH = re.compile(r"(?:^|/)lib/([^/]+)/")

bumped: list[Path] = []


def bump_file(path: Path, pattern: re.Pattern[str]) -> None:
    text = path.read_text("utf-8")
    match = pattern.search(text)
    if not match:
        return
    next_value = int(match.group(2)) + 1
    path.write_text(pattern.sub(rf"\g<1>{next_value}", text, count=1), "utf-8")
    bumped.append(path)
    print(f"Bumped {path} to {next_value}")


def file_contains(path: Path, needle: str) -> bool:
    return path.is_file() and needle in path.read_text("utf-8")


def affected_libs(initial: set[str]) -> set[str]:
    result = set(initial)
    queue = set(initial)
    while queue:
        lib = queue.pop()
        needle = f":lib:{lib}"
        for build_file in Path("lib").glob("*/build.gradle.kts"):
            name = build_file.parent.name
            if name not in result and file_contains(build_file, needle):
                result.add(name)
                queue.add(name)
    return result


def multisrcs_using(lib: str) -> set[str]:
    needle = f":lib:{lib}"
    return {
        build_file.parent.name
        for build_file in Path("lib-multisrc").glob("*/build.gradle.kts")
        if file_contains(build_file, needle)
    }


def extensions_using_lib(lib: str) -> list[Path]:
    needle = f":lib:{lib}"
    return [build_file for build_file in Path("src").glob("*/*/build.gradle") if file_contains(build_file, needle)]


def extensions_using_theme(theme: str) -> list[Path]:
    needle = f"themePkg = '{theme}'"
    return [build_file for build_file in Path("src").glob("*/*/build.gradle") if file_contains(build_file, needle)]


def stage_or_commit(mode: str, commit_message: str) -> None:
    if not bumped or mode == "--dry-run":
        return
    subprocess.check_call(["git", "add", *map(str, bumped)])
    if mode == "--stage-changes":
        return
    subprocess.check_call(["git", "commit", "-m", "[skip ci] chore: bump affected extensions", "-m", f"Caused by: {commit_message}"])


def main() -> None:
    if len(sys.argv) < 3:
        print("Usage: bump-versions.py [<commit message>|--dry-run|--modify-only|--stage-changes] <changed files...>", file=sys.stderr)
        sys.exit(1)

    mode = sys.argv[1]
    changed_files = sys.argv[2:]
    initial_libs = {match.group(1) for match in map(LIB_FROM_PATH.search, changed_files) if match}
    libs = affected_libs(initial_libs)
    print(f"Affected libs: {sorted(libs)}")

    if mode == "--dry-run":
        print("Dry run: files are listed but not changed.")

    themes_to_skip = set()
    for lib in sorted(libs):
        for theme in sorted(multisrcs_using(lib)):
            themes_to_skip.add(theme)
            theme_build = Path("lib-multisrc", theme, "build.gradle.kts")
            if mode != "--dry-run":
                bump_file(theme_build, BASE_VERSION)
            else:
                print(f"Would bump {theme_build}")

    for lib in sorted(libs):
        for build_file in extensions_using_lib(lib):
            if any(build_file in extensions_using_theme(theme) for theme in themes_to_skip):
                continue
            if mode != "--dry-run":
                bump_file(build_file, EXT_VERSION)
            else:
                print(f"Would bump {build_file}")

    if mode not in {"--dry-run", "--modify-only"}:
        stage_or_commit(mode, mode)


if __name__ == "__main__":
    main()
