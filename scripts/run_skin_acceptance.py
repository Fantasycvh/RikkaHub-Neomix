#!/usr/bin/env python3
"""Run the opt-in skin matrix on an installed debug device, without uninstall/clear or user-profile writes.

Build first with -PlongTextBaseline=shared :app:assembleDebug :app:assembleDebugAndroidTest.
Only install -r is used. The runner suppresses production Application startup; tests supply private dependencies.
Evidence and the pre-install backup remain inside ignored app/build/reports/appearance.
"""
import argparse
from datetime import datetime
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "app.androidrik.rikkahub.debug"
RUNNER = PACKAGE + ".test/app.androidrik.rikkahub.perf.LongTextBaselineRunner"
TESTS = "app.androidrik.rikkahub.ui.theme."


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--routes", help="Optional comma-separated subset; report retains uncovered routes")
    parser.add_argument("--method", choices=("light100", "dark100", "light200", "dark200"))
    parser.add_argument("--no-install", action="store_true", help="Reuse the already installed matching APKs")
    args = parser.parse_args()
    if not args.adb:
        parser.error("Provide --adb or add Android platform-tools to PATH")
    if args.routes and not re.fullmatch(r"\w+(,\w+)*", args.routes):
        parser.error("Invalid route list")
    run = ROOT / "app/build/reports/appearance" / ("skin-acceptance-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    run.mkdir(parents=True)
    adb = [args.adb, "-s", args.serial]
    if not args.no_install:
        with (run / "debug-profile-before.tar").open("wb") as backup:
            subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "databases", "files/datastore", "shared_prefs"],
                           stdout=backup, check=True)
        for apk in ("app/build/outputs/apk/debug/app-universal-debug.apk",
                    "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
            subprocess.run(adb + ["install", "-r", str(ROOT / apk)], check=True)
    classes = TESTS + ("SkinPageLargeFontAcceptanceTest" if args.method and args.method.endswith("200") else "SkinPageAcceptanceTest")
    if args.method:
        classes += "#" + args.method
    else:
        classes += "," + TESTS + "SkinPageLargeFontAcceptanceTest," + TESTS + "SkinLargeFontAcceptanceTest," + TESTS + "SkinPerformanceAcceptanceTest"
    command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", classes]
    if args.routes:
        command += ["-e", "skinRoutes", args.routes]
    command += [RUNNER]
    # Remove only our four generated result files, so a failed/partial run cannot reuse old success rows.
    subprocess.run(adb + ["shell", "run-as", PACKAGE, "rm", "-f"] +
                   [f"files/skin-acceptance/{mode}/results.json" for mode in
                    ("light-100", "dark-100", "light-200", "dark-200")] + ["files/skin-performance.json"], check=True)
    with (run / "instrumentation.txt").open("wb") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    log = (run / "instrumentation.txt").read_text(encoding="utf-8", errors="replace")
    # am instrument can exit zero even when a test or runner failed.
    passed = bool(re.search(r"OK \(\d+ tests?\)", log)) and "FAILURES!!!" not in log
    paths = ["files/skin-acceptance"]
    if not args.method:
        paths += ["files/skin-performance.json"]
    archive = subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "tar", "-cf", "-"] + paths, capture_output=True, check=True)
    with tarfile.open(fileobj=io.BytesIO(archive.stdout)) as tar:
        for member in tar.getmembers():
            relative = Path(member.name)
            if not member.isfile():
                continue
            if relative.is_absolute() or ".." in relative.parts or not relative.parts or relative.parts[0] != "files":
                raise ValueError("Unexpected evidence path")
            output = run.joinpath(*relative.parts[1:])
            output.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as source:
                output.write_bytes(source.read())
    (run / "run.json").write_text(json.dumps({"passed": passed, "classes": classes,
        "serial": args.serial, "subset": args.routes}, indent=2), encoding="utf-8")
    print(run)
    print("PASS" if passed else "FAIL: inspect instrumentation.txt")
    return int(not passed)


if __name__ == "__main__":
    raise SystemExit(main())
