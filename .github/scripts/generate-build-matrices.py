#!/usr/bin/env python3

import itertools
import json
import os
import re
import subprocess
import sys
from pathlib import Path

EXTENSION_REGEX = re.compile(r"^src/(?P<lang>[^/]+)/(?P<extension>[^/]+)")
MULTISRC_REGEX = re.compile(r"^lib-multisrc/(?P<multisrc>[^/]+)")
LIB_REGEX = re.compile(r"^lib/(?P<lib>[^/]+)")
CORE_REGEX = re.compile(
    r"^(common/|core/|gradle/|build\.gradle\.kts|gradle\.properties|settings\.gradle\.kts|\.editorconfig|\.github/scripts/)"
)


def run(command: list[str], check: bool = True) -> str:
    result = subprocess.run(command, capture_output=True, text=True)
    if check and result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        sys.exit(result.returncode)
    return result.stdout.strip()


def diff_files(ref: str) -> list[tuple[str, str]]:
    if ref == "ALL":
        return [("A", str(path.as_posix())) for path in Path("src").glob("*/*/build.gradle")]

    diff = run(["git", "diff", "--name-status", f"{ref}...HEAD"], check=False)
    if not diff:
        diff = run(["git", "diff", "--name-status", ref, "HEAD"], check=False)
    if not diff:
        return []

    files = []
    for line in diff.splitlines():
        parts = line.split("\t")
        status = parts[0][:1]
        for file in parts[1:]:
            files.append((status, Path(file).as_posix()))
    return files


def gradle_build_file(path: Path) -> Path | None:
    for name in ("build.gradle", "build.gradle.kts"):
        candidate = path / name
        if candidate.is_file():
            return candidate
    return None


def all_modules() -> tuple[set[str], set[str]]:
    modules = set()
    deleted = set()
    for build_file in Path("src").glob("*/*/build.gradle"):
        lang = build_file.parent.parent.name
        extension = build_file.parent.name
        modules.add(f":src:{lang}:{extension}")
        deleted.add(f"{lang}.{extension}")
    return modules, deleted


def project_dependency_regex(prefix: str, names: set[str]) -> re.Pattern[str] | None:
    if not names:
        return None
    joined = "|".join(map(re.escape, names))
    return re.compile(rf"project\((?:path(?:\s*=|:)\s*)?[\"']:{prefix}:({joined})[\"']\)")


def dependent_libs(libs: set[str]) -> set[str]:
    if not libs:
        return set()

    found = set()
    queue = set(libs)
    while queue:
        regex = project_dependency_regex("lib", queue)
        queue = set()
        for build_file in Path("lib").glob("*/build.gradle.kts"):
            lib = build_file.parent.name
            if lib in libs or lib in found:
                continue
            if regex and regex.search(build_file.read_text("utf-8")):
                found.add(lib)
                queue.add(lib)
    return found


def multisrcs_using_libs(libs: set[str]) -> set[str]:
    regex = project_dependency_regex("lib", libs)
    if regex is None:
        return set()

    result = set()
    for build_file in Path("lib-multisrc").glob("*/build.gradle.kts"):
        if regex.search(build_file.read_text("utf-8")):
            result.add(build_file.parent.name)
    return result


def extensions_using(multisrcs: set[str], libs: set[str]) -> set[tuple[str, str]]:
    patterns = []
    if multisrcs:
        patterns.append(rf"themePkg\s*=\s*[\"']({'|'.join(map(re.escape, multisrcs))})[\"']")
    lib_regex = project_dependency_regex("lib", libs)
    if lib_regex is not None:
        patterns.append(lib_regex.pattern)
    if not patterns:
        return set()

    regex = re.compile("|".join(patterns))
    result = set()
    for build_file in Path("src").glob("*/*/build.gradle"):
        if regex.search(build_file.read_text("utf-8")):
            result.add((build_file.parent.parent.name, build_file.parent.name))
    return result


def modules_for_changes(ref: str) -> tuple[set[str], set[str]]:
    modules = set()
    deleted = set()
    libs = set()
    multisrcs = set()
    core_changed = False

    for status, file in diff_files(ref):
        if CORE_REGEX.search(file):
            core_changed = True
            continue

        if match := EXTENSION_REGEX.search(file):
            lang = match.group("lang")
            extension = match.group("extension")
            deleted.add(f"{lang}.{extension}")
            if status != "D" and gradle_build_file(Path("src", lang, extension)):
                modules.add(f":src:{lang}:{extension}")
            continue

        if match := MULTISRC_REGEX.search(file):
            name = match.group("multisrc")
            if Path("lib-multisrc", name).is_dir():
                multisrcs.add(name)
            continue

        if match := LIB_REGEX.search(file):
            name = match.group("lib")
            if Path("lib", name).is_dir():
                libs.add(name)

    if core_changed:
        return all_modules()

    libs.update(dependent_libs(libs))
    multisrcs.update(multisrcs_using_libs(libs))
    extensions = extensions_using(multisrcs, libs)
    modules.update(f":src:{lang}:{extension}" for lang, extension in extensions)
    deleted.update(f"{lang}.{extension}" for lang, extension in extensions)

    if os.getenv("IS_PR_CHECK") != "true" and Path(".github/always_build.json").is_file():
        for module in json.loads(Path(".github/always_build.json").read_text("utf-8")):
            modules.add(":src:" + module.replace(".", ":"))
            deleted.add(module)

    return modules, deleted


def chunked(values: list[str], size: int) -> list[list[str]]:
    iterator = iter(values)
    return list(iter(lambda: list(itertools.islice(iterator, size)), []))


def main() -> None:
    if len(sys.argv) != 3:
        print("Usage: generate-build-matrices.py <ref|ALL> <Debug|Release>", file=sys.stderr)
        sys.exit(1)

    ref, build_type = sys.argv[1:]
    modules, deleted = modules_for_changes(ref)
    tasks = [f"{module}:assemble{build_type}" for module in sorted(modules)]
    chunks = chunked(tasks, int(os.getenv("CI_CHUNK_SIZE", "15")))
    matrix = {"chunk": [{"number": index + 1, "modules": chunk} for index, chunk in enumerate(chunks)]}

    print("Module chunks to build:")
    print(json.dumps(matrix, indent=2))
    print("Modules to delete from repo output:")
    print(json.dumps(sorted(deleted), indent=2))

    output = os.getenv("GITHUB_OUTPUT")
    if output:
        with Path(output).open("a", encoding="utf-8") as file:
            file.write(f"matrix={json.dumps(matrix)}\n")
            file.write(f"delete={json.dumps(sorted(deleted))}\n")
            file.write(f"has_modules={'true' if chunks else 'false'}\n")


if __name__ == "__main__":
    main()
