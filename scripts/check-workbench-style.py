#!/usr/bin/env python3
"""Run DBeaver's pinned Checkstyle rules and nullability checker; warnings fail the gate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

CHECKSTYLE_VERSION = "10.21.4"  # Same version as the reusable workflow's pinned Docker action.
CHECKER_COMMIT = "a1c3e402ed99a9698f8fe7bb0440b3aab2a596da"
CONFIG_SHA256 = "b48e3b69fefbff584c4a79bb861eaa8b7ed4fc4af9c3355a3015d1053eb80ae0"
ASSETS = {
    "checkstyle-10.21.4-all.jar": (
        "https://github.com/checkstyle/checkstyle/releases/download/checkstyle-10.21.4/checkstyle-10.21.4-all.jar",
        "3c1d94d6ecc83e02dff587c9ba5b6b4ec4fec38c7a958eb587efec9b28e2f318", 20000000),
    "jspecify-1.0.0.jar": (
        "https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
        "1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab", 16384),
    "NullabilityAnnotationsCheck.java": (
        "https://raw.githubusercontent.com/dbeaver/checkstyle-nullability-annotations/" + CHECKER_COMMIT
        + "/src/main/java/sh/adelessfox/checkstyle/checks/NullabilityAnnotationsCheck.java",
        "1de6121d350fe6c3a8f40cbf96813893bc53ee71e9ac5aa7e455bc3f54e2ae67", 16384),
    "checkstyle_packages.xml": (
        "https://raw.githubusercontent.com/dbeaver/checkstyle-nullability-annotations/" + CHECKER_COMMIT
        + "/src/main/resources/checkstyle_packages.xml",
        "b01a1e54687ee80869f1dafeaefb2570fa947988d8ade182fc2027513972dee2", 4096),
    "messages.properties": (
        "https://raw.githubusercontent.com/dbeaver/checkstyle-nullability-annotations/" + CHECKER_COMMIT
        + "/src/main/resources/sh/adelessfox/checkstyle/checks/messages.properties",
        "9586037dcd013e4a1c17f4b66788af23cda8df5462e3387ad861e06ba457ae3e", 4096),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dependencies(cache, java, compiler):
    cache.mkdir(parents=True, exist_ok=True)
    for name, (url, expected, limit) in ASSETS.items():
        target = cache / name
        if not target.exists():
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read(limit + 1)
            if len(data) > limit or hashlib.sha256(data).hexdigest() != expected:
                raise SystemExit("Dependency integrity check failed: " + name)
            target.write_bytes(data)
        if target.stat().st_size > limit or digest(target) != expected:
            raise SystemExit("Cached dependency integrity check failed: " + name)
    checker = cache / "checkstyle-nullability-annotations.jar"
    cp = os.pathsep.join(str(cache / name) for name in ("checkstyle-10.21.4-all.jar", "jspecify-1.0.0.jar"))
    marker = cache / "checker-source.sha256"
    if not checker.exists() or not marker.exists() or marker.read_text().strip() != ASSETS["NullabilityAnnotationsCheck.java"][1]:
        with tempfile.TemporaryDirectory(prefix="dbeaver-style-compiler-") as directory:
            if compiler:
                prefix = [java, "-jar", str(compiler), "-17", "-proc:none", "-nowarn"]
            else:
                javac = Path(java).parent / ("javac.exe" if os.name == "nt" else "javac")
                executable = str(javac) if javac.exists() else shutil.which("javac")
                if not executable:
                    raise SystemExit("Use an existing JDK or provide --compiler with an existing ECJ jar")
                prefix = [executable, "--release", "17", "-proc:none"]
            result = subprocess.run(prefix + ["-classpath", cp, "-d", directory,
                str(cache / "NullabilityAnnotationsCheck.java")], capture_output=True, text=True, timeout=60)
            if result.returncode:
                raise SystemExit((result.stdout + result.stderr)[-8000:])
            with zipfile.ZipFile(checker, "w", compression=zipfile.ZIP_DEFLATED) as output:
                for path in Path(directory).rglob("*.class"):
                    output.write(path, str(path.relative_to(directory)))
                output.write(cache / "checkstyle_packages.xml", "checkstyle_packages.xml")
                output.write(cache / "messages.properties", "sh/adelessfox/checkstyle/checks/messages.properties")
            marker.write_text(ASSETS["NullabilityAnnotationsCheck.java"][1] + "\n")
    return os.pathsep.join([cp, str(checker)])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, help="Existing DBeaver installation, to use its Java runtime")
    parser.add_argument("--java", help="Existing Java executable")
    parser.add_argument("--compiler", type=Path, help="Existing ECJ jar if no JDK compiler is available")
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
                        / "dbeaver-workbench-style")
    parser.add_argument("--files", nargs="+", type=Path, help="Restrict inspection to these files")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--limit", type=int, default=60, help="Maximum printed violations; all still fail the gate")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    config = root / "scripts/checkstyle.xml"
    if digest(config) != CONFIG_SHA256:
        raise SystemExit("Checkstyle configuration differs from the pinned upstream rules")
    java = args.java
    if not java and args.app:
        base = args.app / "Contents/Eclipse" if (args.app / "Contents/Eclipse").is_dir() else args.app
        candidates = [base / "jre/Contents/Home/bin/java", base / "jre/bin/java", base / "jre/bin/java.exe"]
        java = next((str(path) for path in candidates if path.is_file()), None)
    java = java or shutil.which("java")
    if not java:
        raise SystemExit("An existing Java runtime is required")
    cp = dependencies(args.cache.expanduser().resolve(), java, args.compiler)
    core_paths = [
        "plugins/org.jkiss.dbeaver.model.ai/src/org/jkiss/dbeaver/model/ai/AIVisualToolOutput.java",
        "plugins/org.jkiss.dbeaver.model.ai/src/org/jkiss/dbeaver/model/ai/impl/AIAssistantImpl.java",
        "plugins/org.jkiss.dbeaver.ui.ai/src/org/jkiss/dbeaver/ui/ai/chat/controls/WebCSSInitializer.java",
        "plugins/org.jkiss.dbeaver.ui.ai/src/org/jkiss/dbeaver/ui/ai/chat/controls/WebViewMessageList.java",
        "plugins/org.jkiss.dbeaver.ui.ai/src/org/jkiss/dbeaver/ui/ai/chat/controls/WebViewMessageRenderer.java",
    ]
    if args.files:
        files = sorted(set(path.resolve() for path in args.files))
    else:
        bundle = root / "plugins/org.jkiss.dbeaver.ui.ai.workbench"
        files = set(bundle.rglob("*.java"))
        files.update((root / "scripts/java").rglob("*.java"))
        changed = subprocess.run(["git", "diff", "--name-only", "--diff-filter=ACMR", "HEAD", "--", "*.java"],
                                 cwd=root, capture_output=True, text=True, check=True)
        files.update(root / line for line in changed.stdout.splitlines() if line.startswith("plugins/"))
        files.update(root / path for path in core_paths if (root / path).is_file())
        files = sorted(path.resolve() for path in files if path.is_file() and "target" not in path.parts)
    if len(files) > 256 or any(not path.is_relative_to(root) for path in files):
        raise SystemExit("Style scope must contain at most 256 repository Java files")
    result = subprocess.run([java, "-cp", cp, "com.puppycrawl.tools.checkstyle.Main", "-c", str(config), "-f", "xml",
                             *map(str, files)], cwd=root, capture_output=True, text=True, timeout=120)
    if len(result.stdout) > 4 * 1024 * 1024:
        raise SystemExit("Checkstyle report exceeds bounded inspection limit")
    try:
        report = ET.fromstring(result.stdout)
    except ET.ParseError:
        raise SystemExit((result.stderr + result.stdout)[-8000:])
    violations = []
    for file in report.findall("file"):
        relative = str(Path(file.attrib["name"]).relative_to(root))
        for error in file.findall("error"):
            violations.append({"file": relative, "line": int(error.attrib["line"]),
                "column": int(error.attrib.get("column", 0)), "check": error.attrib["source"].rsplit(".", 1)[-1],
                "message": error.attrib["message"][:240]})
    limit = max(0, min(args.limit, 100))
    shown = violations[:limit]
    if args.json:
        while True:
            payload = {"files": len(files), "violations": len(violations), "shown": shown,
                       "omitted": len(violations) - len(shown)}
            rendered = json.dumps(payload, ensure_ascii=False)
            if len(rendered.encode("utf-8")) <= 30000 or not shown:
                break
            shown = shown[:-1]
        print(rendered)
    else:
        lines = [f"Checkstyle {CHECKSTYLE_VERSION}: {len(files)} files; {len(violations)} violations"]
        count = 0
        for item in shown:
            line = f"{item['file']}:{item['line']}:{item['column']} {item['check']}: {item['message']}"
            if len(("\n".join(lines) + "\n" + line).encode("utf-8")) > 29000:
                break
            lines.append(line)
            count += 1
        if len(violations) > count:
            lines.append(f"{len(violations) - count} further violations omitted from display; gate remains failed")
        print("\n".join(lines))
    raise SystemExit(1 if violations or result.returncode else 0)


if __name__ == "__main__":
    main()
