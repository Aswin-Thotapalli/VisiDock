"""Run checks on the project AVD without mistaking boot completion for readiness.

Example: python scripts/Run-AndroidChecks.py --installed --quiet-background
Use --launch to start the AVD if it is absent. APKs must already be built.
Launch defaults: 2048MB for UI/core; 6144MB for vision/all. --memory-mb overrides.
Readiness thresholds are adjustable screening heuristics, not ANR guarantees.
They address observed runnable CPU starvation, boot package churn and host
paging; this runner never closes other apps, touches WSL or retries failed tests.
Quiet mode temporarily disables Photos, Messaging and Wellbeing. Do not use it
for external gallery integration coverage. Logs stay in ignored .tools/.
"""

import argparse
import ctypes
import os
from pathlib import Path
import re
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
OPTIONAL = ("com.google.android.apps.wellbeing", "com.google.android.apps.messaging",
            "com.google.android.apps.photos")
SUITES = {
    "ui": "CardCasePresentationTest CardPresentationTest CropPresentationTest AppPresentationTest VaultInteractionTest CardMotionTest CardTurnRetargetTest MotionInteractionTest AuthPresentationTest ReadingPresentationTest".split(),
    "core": "CaptureSessionTest ImageCropperTest CorrectionMemoryTest ImagePipelineTest PersonalLearningLifecycleTest PersonalEmbeddingsTest MultiPersonScanTest".split(),
    "vision": ["VisualModelDeviceTest"],
    "all": [],
}


def available_mb():
    if os.name != "nt":
        return None
    class MemoryStatus(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
            (name, ctypes.c_ulonglong) for name in
            ("total", "available", "page_total", "page_available", "virtual_total", "virtual_available", "extended")]
    status = MemoryStatus()
    status.length = ctypes.sizeof(status)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        raise RuntimeError("Cannot read Windows available physical memory")
    return status.available / (1024 * 1024)


def require_memory(minimum):
    free = available_mb()
    if free is not None and free < minimum:
        raise RuntimeError(f"Host memory insufficient: {free:.0f} MB available; guard requires {minimum} MB. Close unused apps or stop an unused build daemon, then rerun. No automatic retry.")
    return free


def cpu_times(text):
    line = next(line for line in text.splitlines() if line.startswith("cpu "))
    ticks = list(map(int, line.split()[1:9]))  # guest ticks already included in user/nice
    return sum(ticks), ticks[3], ticks[4]


def passed(output):
    match = re.search(r"^OK \((\d+) tests?\)\s*$", output, re.M)
    return bool(match and int(match.group(1)) > 0 and not re.search(
        r"FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_STATUS_CODE: -(?:1|2)\s*$",
        output, re.M))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=SUITES, default="ui")
    parser.add_argument("--installed", action="store_true")
    parser.add_argument("--quiet-background", action="store_true")
    parser.add_argument("--launch", action="store_true")
    parser.add_argument("--serial")
    parser.add_argument("--memory-mb", type=int, help="Launch RAM; default 6144 for vision/all, 2048 for ui/core")
    parser.add_argument("--min-host-free-mb", type=int, default=1536)
    parser.add_argument("--min-idle-percent", type=float, default=15)
    parser.add_argument("--max-iowait-percent", type=float, default=15)
    parser.add_argument("--settled-samples", type=int, default=3)
    parser.add_argument("--sample-seconds", type=float, default=5)
    parser.add_argument("--ready-timeout", type=int, default=240)
    parser.add_argument("--test-timeout", type=int, default=2600)
    args = parser.parse_args()
    if args.memory_mb is None:
        args.memory_mb = 6144 if args.suite in ("vision", "all") else 2048
    if min(args.memory_mb, args.min_host_free_mb, args.settled_samples,
           args.sample_seconds, args.ready_timeout, args.test_timeout) <= 0:
        parser.error("Memory, sampling and timeout values must be positive")
    if not (0 <= args.min_idle_percent <= 100 and 0 <= args.max_iowait_percent <= 100):
        parser.error("CPU percentages must be between 0 and 100")
    project_sdk = ROOT / ".tools/android-sdk"
    sdk = project_sdk if project_sdk.is_dir() else Path(os.environ.get("ANDROID_SDK_ROOT", project_sdk))
    adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    logs = ROOT / ".tools" / ("android-checks-" + time.strftime("%Y%m%d-%H%M%S"))
    logs.mkdir(parents=True, exist_ok=True)

    def command(parts, timeout=30):
        return subprocess.check_output([str(adb), *parts], text=True, errors="replace", timeout=timeout, stderr=subprocess.STDOUT)

    def shell(*parts, timeout=30):
        return command(["-s", serial, "shell", *parts], timeout)

    def devices():
        return [line.split()[0] for line in command(["devices"]).splitlines()[1:]
                if len(line.split()) == 2 and line.split()[1] == "device"]

    def owned(serial):
        return serial.startswith("emulator-") and command(["-s", serial, "shell", "getprop", "ro.boot.qemu.avd_name"]).strip() == "VisiDock_Test"

    require_memory(args.min_host_free_mb)
    found = [serial for serial in devices() if owned(serial)]
    serial = args.serial
    if serial and not owned(serial):
        raise RuntimeError("Selected serial is not the project VisiDock_Test emulator")
    if not serial and len(found) == 1:
        serial = found[0]
    elif not serial and len(found) > 1:
        raise RuntimeError("Multiple project emulators found; specify --serial")
    if not serial:
        if not args.launch:
            raise RuntimeError("Project emulator absent. Start VisiDock_Test or pass --launch.")
        require_memory(args.memory_mb + args.min_host_free_mb)
        env = dict(os.environ, ANDROID_AVD_HOME=str(ROOT / ".tools/avd"))
        emulator = sdk / "emulator" / ("emulator.exe" if os.name == "nt" else "emulator")
        with (logs / "emulator.log").open("w") as log:
            process = subprocess.Popen([str(emulator), "-avd", "VisiDock_Test", "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-gpu", "swiftshader_indirect", "-memory", str(args.memory_mb), "-cores", "4"], env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        deadline = time.monotonic() + args.ready_timeout
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"Emulator exited. See {logs / 'emulator.log'}")
            found = [device for device in devices() if owned(device)]
            if len(found) == 1:
                serial = found[0]
                break
            time.sleep(3)
        if not serial:
            raise RuntimeError("Project emulator did not appear within readiness timeout")
    print(f"Verified {serial}: VisiDock_Test. Logs: {logs}", flush=True)
    disabled_by_us = []
    original_settings = {}
    diagnostics_attempted = False

    def capture_failure():
        # Best effort only: diagnostics must never obscure the failed readiness/test gate.
        # Keep contents local; logcat may contain account information or card OCR.
        nonlocal diagnostics_attempted
        if diagnostics_attempted:
            return
        diagnostics_attempted = True
        for filename, parts in (
            ("failure-logcat.txt", ["logcat", "-b", "all", "-d", "-v", "threadtime", "-t", "6000"]),
            ("failure-exit-info.txt", ["shell", "dumpsys", "activity", "exit-info", "com.thotapalli.visidock.demo"]),
        ):
            try:
                with (logs / filename).open("w", encoding="utf-8") as log:
                    subprocess.run([str(adb), "-s", serial, *parts], stdout=log,
                                   stderr=subprocess.STDOUT, timeout=20, check=True)
            except Exception as error:
                try:
                    with (logs / "diagnostic-errors.txt").open("a", encoding="utf-8") as log:
                        log.write(f"{filename}: {type(error).__name__}\n")
                except OSError:
                    pass
        print(f"Failure diagnostics attempted; inspect local files in {logs}", flush=True)

    try:
        deadline = time.monotonic() + args.ready_timeout
        while shell("getprop", "sys.boot_completed").strip() != "1":
            require_memory(args.min_host_free_mb)
            if time.monotonic() >= deadline:
                raise RuntimeError("Boot completion timed out")
            time.sleep(3)
        if args.quiet_background:
            installed = set(shell("pm", "list", "packages").splitlines())
            disabled = set(shell("pm", "list", "packages", "-d", "--user", "0").splitlines())
            for package in OPTIONAL:
                if f"package:{package}" in installed and f"package:{package}" not in disabled:
                    details = shell("dumpsys", "package", package)
                    state = re.search(r"User 0:.*?\benabled=(\d+)", details)
                    if not state or state.group(1) not in ("0", "1"):
                        raise RuntimeError(f"Cannot safely preserve enabled state for {package}")
                    restore = "default-state" if state.group(1) == "0" else "enable"
                    disabled_by_us.append((package, restore))  # restore even if outcome is unknown
                    response = shell("pm", "disable-user", "--user", "0", package)
                    if "disabled-user" not in response:
                        raise RuntimeError(f"Could not confirm temporary disable: {package}: {response}")

        def wait_ready(stage):
            deadline = time.monotonic() + args.ready_timeout
            previous = cpu_times(shell("cat", "/proc/stat"))
            stable = 0
            with (logs / "readiness.log").open("a") as log:
                while time.monotonic() < deadline:
                    time.sleep(args.sample_seconds)
                    free = require_memory(args.min_host_free_mb)
                    current = cpu_times(shell("cat", "/proc/stat"))
                    total = current[0] - previous[0]
                    if total <= 0:
                        stable = 0
                        previous = current
                        continue
                    idle = 100 * (current[1] - previous[1]) / total
                    io = 100 * (current[2] - previous[2]) / total
                    previous = current
                    stable = stable + 1 if idle >= args.min_idle_percent and io <= args.max_iowait_percent else 0
                    line = f"{stage}: guest idle={idle:.1f}% iowait={io:.1f}% host-free={free if free is None else round(free)}MB settled={stable}/{args.settled_samples}"
                    print(line, flush=True)
                    log.write(line + "\n")
                    log.flush()
                    if stable >= args.settled_samples:
                        return
            raise RuntimeError(f"{stage}: emulator never settled within {args.ready_timeout}s; see readiness.log. No test retry was attempted.")

        wait_ready("before install")
        if not args.installed:
            for relative in ("app/build/outputs/apk/demo/debug/app-demo-debug.apk", "app/build/outputs/apk/androidTest/demo/debug/app-demo-debug-androidTest.apk"):
                apk = ROOT / relative
                if not apk.is_file():
                    raise RuntimeError(f"Build missing: {apk}")
                print(f"Installing {apk.name}", flush=True)
                require_memory(args.min_host_free_mb)
                output = command(["-s", serial, "install", "--no-streaming", "-r", str(apk)], timeout=300)
                if "Success" not in output:
                    raise RuntimeError(f"Installation not confirmed: {output}")
        for namespace, key, value in (("system", "font_scale", "1.0"), ("global", "animator_duration_scale", "1.0")):
            original_settings[namespace, key] = shell("settings", "get", namespace, key).strip()
            shell("settings", "put", namespace, key, value)
        wait_ready("before instrumentation")
        selection = ["-e", "class", ",".join("com.thotapalli.visidock." + name for name in SUITES[args.suite])] if SUITES[args.suite] else []
        result_path = logs / "instrumentation.log"
        command(["-s", serial, "logcat", "-b", "all", "-c"])
        print(f"Starting {args.suite} checks; output: {result_path}", flush=True)
        with result_path.open("w", encoding="utf-8") as log:
            proc = subprocess.Popen([str(adb), "-s", serial, "shell", "am", "instrument", "-w", "-r", *selection, "-e", "visualModelTest", "true", "com.thotapalli.visidock.demo.test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + args.test_timeout
            try:
                while proc.poll() is None:
                    try:
                        proc.wait(timeout=min(30, max(1, deadline - time.monotonic())))
                    except subprocess.TimeoutExpired:
                        print(f"Checks still running. Read {result_path}", flush=True)
                    if time.monotonic() >= deadline and proc.poll() is None:
                        raise RuntimeError("Instrumentation timed out. No retry. Inspect the emulator before rerunning.")
            except BaseException:
                capture_failure()  # preserve evidence before the intentional force-stop
                try:
                    proc.terminate()
                    proc.wait(timeout=10)
                    # Host adb termination need not terminate guest instrumentation.
                    shell("am", "force-stop", "com.thotapalli.visidock.demo")
                except Exception:
                    print("Instrumentation cleanup was incomplete; inspect the emulator before rerunning.", file=sys.stderr, flush=True)
                raise
        output = result_path.read_text(encoding="utf-8", errors="replace")
        if proc.returncode != 0 or not passed(output):
            raise RuntimeError(f"Checks failed or no positive test count was reported. See {result_path}")
        print(re.search(r"^OK \(\d+ tests?\)", output, re.M).group(0), flush=True)
    except BaseException:
        capture_failure()
        raise
    finally:
        original_failure = sys.exc_info()[0] is not None
        failures = []
        for (namespace, key), value in original_settings.items():
            try:
                shell("settings", "delete", namespace, key) if value == "null" else shell("settings", "put", namespace, key, value)
            except Exception as error:
                failures.append(f"restore {namespace}/{key}: {error}")
        for package, restore in reversed(disabled_by_us):
            try:
                response = shell("pm", restore, "--user", "0", package)
                if "new state:" not in response:
                    raise RuntimeError(response)
            except Exception as error:
                failures.append(f"re-enable {package}: {error}")
        if failures:
            try:
                (logs / "cleanup-errors.txt").write_text("\n".join(failures), encoding="utf-8")
            except OSError:
                pass
            message = f"Cleanup incomplete; inspect {logs / 'cleanup-errors.txt'} before rerunning."
            if original_failure:
                print(message, file=sys.stderr, flush=True)
            else:
                raise RuntimeError(message)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
