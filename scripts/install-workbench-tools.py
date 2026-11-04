#!/usr/bin/env python3
"""Install the bounded addon and theme APIs into a stopped Community installation."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

PREFIXES = {
    "org.jkiss.dbeaver.ui.ai": ("org/jkiss/dbeaver/ui/ai/chat/controls/WebCSSInitializer", "org/jkiss/dbeaver/ui/ai/chat/controls/WebViewMessageList", "org/jkiss/dbeaver/ui/ai/chat/controls/WebViewMessageRenderer"),
    "org.jkiss.dbeaver.model.ai": ("org/jkiss/dbeaver/model/ai/impl/AIAssistantImpl",
        "org/jkiss/dbeaver/model/ai/AIVisualToolOutput"),
}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--restore-originals", action="store_true", help="Restore the exact retained core bundles while stopped")
    args = parser.parse_args()
    eclipse = args.app / "Contents/Eclipse" if (args.app / "Contents/Eclipse").is_dir() else args.app
    # Refuse to replace classes while the product has live work.
    if subprocess.run(["pgrep", "-x", "dbeaver"], capture_output=True).returncode == 0:
        raise SystemExit("Close DBeaver normally before installing")
    if args.restore_originals:
        originals = eclipse / "configuration/workbench-tools-originals"
        history = json.loads((originals / "receipt.json").read_text())
        for symbolic, receipt in history.items():
            if symbolic not in PREFIXES:
                raise SystemExit("Unexpected original bundle receipt")
            candidates = list(originals.glob(symbolic + "_*.jar.original"))
            if len(candidates) != 1 or digest(candidates[0]) != receipt["originalSha256"]:
                raise SystemExit("Original bundle receipt mismatch")
            destination = eclipse / "plugins" / candidates[0].name.removesuffix(".original")
            if digest(destination) not in {receipt["installedSha256"], receipt["originalSha256"]}:
                raise SystemExit("Installed bundle changed outside this installer")
            shutil.copyfile(candidates[0], destination)
        print("Restored exact original core bundles; disable the addon before restarting")
        return
    root = Path(__file__).resolve().parents[1]
    bundle = root / "plugins/org.jkiss.dbeaver.ui.ai.workbench"
    version = next(x.split(": ", 1)[1].removesuffix(".qualifier") for x in (bundle / "META-INF/MANIFEST.MF").read_text().splitlines() if x.startswith("Bundle-Version: "))
    addon = bundle / f"target/org.jkiss.dbeaver.ui.ai.workbench_{version}.jar"
    patch = bundle / "target/workbench-theme-api.zip"
    if not addon.is_file() or addon.stat().st_size > 1024*1024 or not patch.is_file() or patch.stat().st_size > 256*1024:
        raise SystemExit("Build the bounded artifacts first")
    if shutil.disk_usage(eclipse).free < 64*1024*1024:
        raise SystemExit("Installation requires a 64 MiB free-space reserve")
    with zipfile.ZipFile(patch) as source:
        replacements = {}
        for info in source.infolist():
            name = info.filename
            if info.file_size > 128*1024 or not name.endswith(".class") or not any(name.startswith(p + ".") or name.startswith(p + "$") for prefixes in PREFIXES.values() for p in prefixes):
                raise SystemExit("Unexpected class in theme API patch")
            replacements[name] = source.read(info)
    originals = eclipse / "configuration/workbench-tools-originals"
    originals.mkdir(parents=True, exist_ok=True)
    receipt = originals / "receipt.json"
    history = json.loads(receipt.read_text()) if receipt.exists() else {}
    targets = []
    for symbolic, prefixes in PREFIXES.items():
        jars = list((eclipse / "plugins").glob(symbolic + "_*.jar"))
        if len(jars) != 1 or jars[0].stat().st_size > 2*1024*1024:
            raise SystemExit("Expected one bounded installed bundle: " + symbolic)
        installed = jars[0]
        original = originals / (installed.name + ".original")
        if original.exists():
            if symbolic not in history or digest(original) != history[symbolic]["originalSha256"]:
                raise SystemExit("Original bundle receipt mismatch")
            if digest(installed) not in {history[symbolic]["installedSha256"], history[symbolic]["originalSha256"]}:
                raise SystemExit("Installed bundle changed outside this installer")
        else:
            with zipfile.ZipFile(installed) as jar:
                if any(n.upper().endswith((".SF", ".RSA", ".DSA")) for n in jar.namelist()):
                    raise SystemExit("Signed bundles require a full product build")
            shutil.copyfile(installed, original)
            history[symbolic] = {"originalSha256": digest(original)}
        targets.append((symbolic, prefixes, installed, original))
    # One bounded candidate per bundle; exact originals retained once for rollback.
    for symbolic, prefixes, installed, original in targets:
        with tempfile.TemporaryDirectory(prefix="dbeaver-theme-install-", dir=originals) as temporary:
            candidate = Path(temporary) / "bundle.jar"
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(candidate, "w", compression=zipfile.ZIP_DEFLATED) as output:
                for info in source.infolist():
                    if any(info.filename.startswith(p + ".") or info.filename.startswith(p + "$") for p in prefixes):
                        continue
                    output.writestr(info, source.read(info))
                for name, contents in replacements.items():
                    if any(name.startswith(p + ".") or name.startswith(p + "$") for p in prefixes):
                        output.writestr(name, contents)
            if candidate.stat().st_size > 2*1024*1024:
                raise SystemExit("Patched bundle exceeds budget")
            candidate.replace(installed)
            history[symbolic]["installedSha256"] = digest(installed)
            receipt.write_text(json.dumps(history, indent=2) + "\n")
    dropins = eclipse / "dropins"
    dropins.mkdir(exist_ok=True)
    destination = dropins / addon.name
    shutil.copyfile(addon, destination)
    print("Installed addon " + version + "; original core bundles retained once (" + str(sum(p.stat().st_size for p in originals.glob("*.original"))) + " bytes)")
    print("Installed addon SHA256: " + digest(destination))

if __name__ == "__main__":
    main()
