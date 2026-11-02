"""P1b device acceptance with a controlled backend; preserves provider settings and notification permission."""
import argparse
import json
import pathlib
import re
import subprocess
from verify_task_runtime import adb, assert_success, provider_settings_fingerprint, RUNNER, PACKAGE, TEST, evidence


def verify(args):
    output = pathlib.Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    package = adb("shell", "dumpsys", "package", PACKAGE)
    granted = re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false)", package)
    if not granted:
        raise RuntimeError("Could not determine debug notification permission; refusing to change it")
    original_permission = granted.group(1) == "true"
    original_settings = provider_settings_fingerprint()
    results = {}

    def run(name, classes, extra=()):
        command = ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", classes, *extra, RUNNER]
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
        (output / f"{name}.log").write_text(result.stdout + result.stderr, encoding="utf-8")
        assert_success(result.stdout)
        results[name] = "passed"

    def approval(mode):
        run("approval-" + mode, TEST, ["-e", "runtimeBaseUrl", args.base_url,
            "-e", "runtimeModel", "runtime-fixture", "-e", "runtimeScenario", "long",
            "-e", "runtimeSeconds", "1", "-e", "runtimeApproval", mode])
        results["approval-" + mode] = evidence(f"approval-{mode}.passed")

    notification_test = "app.androidrik.rikkahub.data.task.TaskNotificationInstrumentedTest"
    try:
        adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        run("platform-notifications", notification_test + "#disabledChannelIsNotAnApprovalRoute," +
            notification_test + "#parallelCompletionNotificationsHaveIndependentPendingIntentsAndCancellation")
        approval("approve")
        approval("deny")
        # Permission changes occur between instrumentation processes; revoking within one can kill it.
        adb("shell", "pm", "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        run("permission-denied", notification_test + "#deniedPermissionIsNotAnApprovalRoute",
            ["-e", "expectNotificationsDenied", "true"])
        approval("unavailable")
        run("database-and-ui", "app.androidrik.rikkahub.data.db.AgentRunDeliveryInstrumentedTest,"
            "app.androidrik.rikkahub.data.task.NoticeUiAcceptanceTest")
        image = subprocess.run(["adb", "exec-out", "run-as", PACKAGE, "cat",
            "files/runtime-acceptance/p1b-task-tree.png"], capture_output=True, check=True)
        (output / "task-tree.png").write_bytes(image.stdout)
    finally:
        adb("shell", "pm", "grant" if original_permission else "revoke", PACKAGE,
            "android.permission.POST_NOTIFICATIONS")
    if provider_settings_fingerprint() != original_settings:
        raise RuntimeError("Provider settings changed; stop and inspect without restoring over user edits")
    results["provider_settings_unchanged"] = True
    results["notification_permission_restored"] = True
    (output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(json.dumps(results, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:19000/v1")
    parser.add_argument("--output", default=".tmp/task-runtime-p1b-evidence")
    verify(parser.parse_args())
