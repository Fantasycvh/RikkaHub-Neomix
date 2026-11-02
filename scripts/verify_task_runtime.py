"""Opt-in device acceptance against an installed debug app and isolated test runner.

Use task_runtime_fixture.py for controlled tests. A real-model proxy is supplied by
the operator; credentials stay on that proxy, never in instrumentation arguments.
"""
import argparse
import json
import pathlib
import subprocess
import time

PACKAGE = "app.androidrik.rikkahub.debug"
RUNNER = PACKAGE + ".test/app.androidrik.rikkahub.data.repository.MemoryIsolationTestRunner"
TEST = "app.androidrik.rikkahub.data.task.TaskRuntimeAcceptanceTest"
FILES = "files/runtime-acceptance/"


def adb(*args, check=True):
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=check).stdout


def evidence(name):
    return adb("shell", "run-as", PACKAGE, "cat", FILES + name, check=False).strip()


def provider_settings_fingerprint():
    """Read-only checksum; no provider URLs, models or credentials are printed/copied."""
    value = adb("shell", "run-as", PACKAGE, "sha256sum",
                "files/datastore/settings.preferences_pb", check=False).strip()
    return value.split()[0] if value else "missing"


def command(args, scenario):
    return ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
            "-e", "runtimeBaseUrl", args.base_url, "-e", "runtimeModel", args.model,
            "-e", "runtimeScenario", scenario, "-e", "runtimeSeconds", str(args.seconds),
            "-e", "runtimeCarrier", args.carrier,
            "-e", "runtimeProtocol", args.protocol,
            RUNNER]


def wait_marker(process, name, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if evidence(name):
            return
        if process.poll() is not None:
            raise RuntimeError(f"Instrumentation stopped before {name}")
        time.sleep(1)
    raise TimeoutError(name)


def assert_success(log):
    if "OK (" not in log or "Process crashed" in log or "FAILURES!!!" in log:
        raise RuntimeError("Instrumentation failed; inspect saved log")


def verify(args):
    output = pathlib.Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    instrumentation = adb("shell", "pm", "list", "instrumentation")
    if RUNNER not in instrumentation:
        raise RuntimeError("Install debug APK and tests with -PmemoryIsolatedTests=true first")
    result = {"backend": args.backend, "carrier": args.carrier, "requested_seconds": args.seconds}
    original_settings = provider_settings_fingerprint()
    # Only test-owned markers are removed. The test DB and every release profile are preserved.
    for name in ("tool.started", "long.passed", "seed.ready", "restart.passed"):
        adb("shell", "run-as", PACKAGE, "rm", "-f", FILES + name)
    originally_awake = "mWakefulness=Awake" in adb("shell", "dumpsys", "power")
    battery = adb("shell", "dumpsys", "battery")
    if args.doze and "UPDATES STOPPED" in battery.upper():
        raise RuntimeError("Battery is already overridden; restore its prior test before --doze")
    doze_changed = False
    doze_killed = False
    with (output / "long.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command(args, "long"), stdout=log, stderr=subprocess.STDOUT)
        try:
            wait_marker(process, "tool.started")
            if args.doze:
                doze_changed = True
                adb("shell", "dumpsys", "battery", "unplug")
                adb("shell", "input", "keyevent", "223")
                state = adb("shell", "dumpsys", "deviceidle", "force-idle", "deep")
                idle = adb("shell", "dumpsys", "deviceidle", "get", "deep").strip()
                result["forced_idle"] = {"command": state.strip(), "state": idle}
                if idle != "IDLE":
                    raise RuntimeError("Device did not enter deep IDLE")
                time.sleep(15)
                result["pid_during_idle"] = adb("shell", "pidof", PACKAGE, check=False).strip()
                doze_killed = not result["pid_during_idle"]
                adb("shell", "dumpsys", "deviceidle", "unforce")
                adb("shell", "dumpsys", "battery", "reset")
                if originally_awake:
                    adb("shell", "input", "keyevent", "224")
            process.wait(timeout=args.seconds + 150)
        finally:
            if process.poll() is None:
                adb("shell", "am", "force-stop", PACKAGE)
                process.wait(timeout=15)
            if doze_changed:
                adb("shell", "dumpsys", "deviceidle", "unforce")
                adb("shell", "dumpsys", "battery", "reset")
                if originally_awake:
                    adb("shell", "input", "keyevent", "224")
    if doze_killed:
        # Android/OEM may kill an unprotected process. The contract is interruption +
        # durable delivery after restart, never a promise of unlimited background life.
        recovered = subprocess.run(command(args, "verify"), capture_output=True, text=True, timeout=90)
        (output / "doze-restart.log").write_text(recovered.stdout + recovered.stderr, encoding="utf-8")
        assert_success(recovered.stdout)
        result["doze_outcome"] = "process killed; interrupted result delivered after restart"
        with (output / "long-retry.log").open("w", encoding="utf-8") as log:
            subprocess.run(command(args, "long"), stdout=log, stderr=subprocess.STDOUT,
                           timeout=args.seconds + 150, check=True)
        assert_success((output / "long-retry.log").read_text(encoding="utf-8"))
    else:
        assert_success((output / "long.log").read_text(encoding="utf-8"))
    result["long"] = evidence("long.passed")
    adb("shell", "run-as", PACKAGE, "rm", "-f", FILES + "seed.ready", FILES + "tool.started")
    with (output / "seed.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command(args, "seed"), stdout=log, stderr=subprocess.STDOUT)
        try:
            wait_marker(process, "seed.ready")
            result["killed_pid"] = adb("shell", "pidof", PACKAGE).strip()
        finally:
            # Kill the target only after run persistence/tool entry; verify uses a fresh process.
            adb("shell", "am", "force-stop", PACKAGE)
            process.wait(timeout=15)
    verified = subprocess.run(command(args, "verify"), capture_output=True, text=True, timeout=90)
    (output / "restart.log").write_text(verified.stdout + verified.stderr, encoding="utf-8")
    assert_success(verified.stdout)
    result["restart"] = evidence("restart.passed")
    ui = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
             "app.androidrik.rikkahub.data.task.NoticeUiAcceptanceTest", RUNNER)
    (output / "ui.log").write_text(ui, encoding="utf-8")
    assert_success(ui)
    for carrier in ("system", "user"):
        image = subprocess.run(["adb", "exec-out", "run-as", PACKAGE, "cat",
                                FILES + f"notice-{carrier}.png"], capture_output=True, check=True)
        (output / f"notice-{carrier}.png").write_bytes(image.stdout)
    if provider_settings_fingerprint() != original_settings:
        raise RuntimeError("App settings changed during acceptance; stop and inspect, do not restore over user edits")
    result["app_provider_settings_unchanged"] = True
    (output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="")
    parser.add_argument("--protocol", choices=("claude", "openai"), default="claude")
    parser.add_argument("--model", required=True)
    parser.add_argument("--backend", choices=("controlled", "real"), required=True)
    parser.add_argument("--seconds", type=int, default=180, choices=range(1, 181))
    parser.add_argument("--carrier", choices=("system", "user"), default="system")
    parser.add_argument("--doze", action="store_true")
    parser.add_argument("--output", default=".tmp/runtime-acceptance/evidence")
    verify(parser.parse_args())
