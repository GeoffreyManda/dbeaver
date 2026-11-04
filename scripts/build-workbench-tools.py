#!/usr/bin/env python3
"""Build the small addon against an existing DBeaver installation, without copying it."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def run(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise SystemExit((result.stdout + result.stderr)[-24000:])
    if result.stdout.strip():
        print(result.stdout.strip()[:4000])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--compiler", type=Path, help="Optional Eclipse ECJ compiler jar")
    parser.add_argument("--online-test", action="store_true", help="Fetch the public official Python CSV page")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    bundle = root / "plugins/org.jkiss.dbeaver.ui.ai.workbench"
    eclipse = args.app / "Contents/Eclipse" if (args.app / "Contents/Eclipse").is_dir() else args.app
    jars = sorted((eclipse / "plugins").glob("*.jar"))
    if not jars:
        raise SystemExit("No DBeaver plugin jars found")
    classpath = os.pathsep.join(str(path.resolve()) for path in jars)
    java_candidates = [eclipse / "jre/Contents/Home/bin/java", eclipse / "jre/bin/java",
                       eclipse / "jre/bin/java.exe"]
    java = next((str(p) for p in java_candidates if p.is_file()), shutil.which("java"))
    if not java:
        raise SystemExit("Java runtime not found")
    compiler = [java, "-jar", str(args.compiler.resolve()), "-21", "-proc:none", "-nowarn"] if args.compiler else [
        shutil.which("javac") or "javac", "--release", "21", "-proc:none"]
    sources = sorted((bundle / "src").rglob("*.java"))
    tests = sorted((bundle / "tests").rglob("*.java"))
    # Both compilation trees are temporary and are removed even after failures.
    with tempfile.TemporaryDirectory(prefix="dbeaver-workbench-") as directory:
        classes = Path(directory) / "classes"
        test_classes = Path(directory) / "tests"
        classes.mkdir()
        test_classes.mkdir()
        run(compiler + ["-classpath", classpath, "-d", str(classes)] + list(map(str, sources)))
        run(compiler + ["-classpath", os.pathsep.join([str(classes), classpath]), "-d", str(test_classes)] + list(map(str, tests)))
        for test in tests:
            name = ".".join(test.relative_to(bundle / "tests").with_suffix("").parts)
            run([java, "-ea", "-Ddbeaver.workbench.onlineTests=" + str(args.online_test).lower(),
                 "-classpath", os.pathsep.join([str(classes), str(test_classes), classpath]), name])
        artifact = bundle / "target/org.jkiss.dbeaver.ui.ai.workbench_0.1.2.jar"
        artifact.parent.mkdir(exist_ok=True)
        with zipfile.ZipFile(artifact, "w", compression=zipfile.ZIP_DEFLATED) as output:
            for name in ("META-INF/MANIFEST.MF", "plugin.xml", "plugin.properties"):
                output.write(bundle / name, name)
            output.write(root / "LICENSE.md", "LICENSE.md")
            for path in sorted((bundle / "resources").rglob("*")):
                if path.is_file() and path.suffix in {".md", ".py", ".R", ".sql"}:
                    output.write(path, str(path.relative_to(bundle)))
            for path in sorted((bundle / "src").rglob("*.properties")):
                output.write(path, str(path.relative_to(bundle / "src")))
            for path in sorted(classes.rglob("*.class")):
                output.write(path, str(path.relative_to(classes)))
        if artifact.stat().st_size > 1024 * 1024:
            artifact.unlink()
            raise SystemExit("Addon exceeds 1 MiB artifact limit")
        print(f"Compiled against {len(jars)} existing plugin jars; tests passed")
        print(f"Artifact: {artifact} ({artifact.stat().st_size} bytes)")
        print("SHA256: " + hashlib.sha256(artifact.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
