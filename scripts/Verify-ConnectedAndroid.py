#!/usr/bin/env python3
"""Opt-in demo-only Android validation; never starts an emulator or clears data."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.thotapalli.visidock.demo"
CLASSES = (
    "AppPresentationTest AuthPresentationTest CaptureSessionTest CardCasePresentationTest "
    "CardMotionTest CardPresentationTest CardTurnRetargetTest CorrectionMemoryTest "
    "CropPresentationTest ImageCropperTest ImagePipelineTest MotionInteractionTest "
    "MultiPersonScanTest PersonalEmbeddingsTest PersonalLearningLifecycleTest "
    "ReadingPresentationTest VaultInteractionTest SmallPrintEvidenceTest DraftStoreTest "
    "EditorFeaturesTest ImportQueueTest OfflineCardRepositoryTest QrPreviewTest"
).split()


def command(args, timeout=30, check=True):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({Path(str(args[0])).name}, exit {result.returncode})")
    return result.stdout + result.stderr


def find_tool(name):
    executable = name + (".exe" if os.name == "nt" else "")
    if shutil.which(executable):
        return Path(shutil.which(executable))
    roots = [ROOT / ".tools/android-sdk"]
    roots += [Path(os.environ[n]) for n in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(n)]
    for sdk in roots:
        candidates = [sdk / "platform-tools" / executable] if name == "adb" else sorted(
            (sdk / "build-tools").glob("*/" + executable), reverse=True)
        for candidate in candidates:
            if candidate.is_file():
                return candidate
    raise RuntimeError(f"{name} unavailable; install/configure Android SDK tools first")


def check_result(text):
    codes = [int(v) for v in re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)", text, re.M)]
    count = re.search(r"OK \((\d+) tests?\)", text)
    if any(code < 0 for code in codes):
        raise RuntimeError("Instrumentation reported failed, ignored, or assumption-skipped tests")
    if re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed", text):
        raise RuntimeError("Instrumentation failed or crashed")
    if not count or int(count[1]) == 0 or codes.count(0) != int(count[1]):
        raise RuntimeError("Missing or inconsistent completed-test evidence")
    if not re.search(r"^INSTRUMENTATION_CODE:\s*-1\s*$", text, re.M):
        raise RuntimeError("Missing successful instrumentation completion")
    return int(count[1])


MODEL_DIRECTORY = "files/visual-model"
MODEL_PARTIAL = MODEL_DIRECTORY + "/test-upload.partial"
MODEL_TARGET = MODEL_DIRECTORY + "/gemma-4-e2b.litertlm"


def model_pins(source=None):
    if source is None:
        source = (ROOT / "app/src/main/java/com/thotapalli/visidock/VisualModel.kt").read_text(encoding="utf-8")
    sizes = re.findall(r"^\s*const val BYTES = ([1-9][0-9]*)L\s*$", source, re.M)
    hashes = re.findall(r'^\s*const val SHA256 = "([a-f0-9]{64})"\s*$', source, re.M)
    if len(sizes) != 1 or len(hashes) != 1:
        raise RuntimeError("Cannot read unique model size and SHA256 pins from VisualModel.kt")
    return {"bytes": int(sizes[0]), "sha256": hashes[0]}


def verify_model_file(path, pins):
    path = Path(path).resolve(strict=True)
    if not path.is_file() or path.stat().st_size != pins["bytes"]:
        raise RuntimeError("Local model does not match the pinned byte size")
    digest = hashlib.sha256()
    count = 0
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            count += len(chunk)
            if count > pins["bytes"]:
                raise RuntimeError("Local model changed while being verified")
            digest.update(chunk)
    if count != pins["bytes"] or digest.hexdigest() != pins["sha256"]:
        raise RuntimeError("Local model does not match the pinned SHA256")
    return path


def upload_verified_model(device, path, pins):
    # Called only after --run and the fixed demo APK identity/install checks.
    # Never interpolate the local path into a remote shell command: it is binary stdin only.
    if PACKAGE != "com.thotapalli.visidock.demo":
        raise RuntimeError("Model upload is restricted to the demo package")
    remote = device + ["shell", "run-as", PACKAGE]
    command(remote + ["mkdir", "-p", MODEL_DIRECTORY])
    try:
        with path.open("rb") as source:
            result = subprocess.run([str(v) for v in device + ["exec-in", "run-as", PACKAGE,
                                    "tee", MODEL_PARTIAL]], stdin=source, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.PIPE, timeout=900)
        if result.returncode:
            raise RuntimeError("Demo model transfer failed; verified target was not replaced")
        size = command(remote + ["stat", "-c", "%s", MODEL_PARTIAL], timeout=30).strip()
        digest = command(remote + ["sha256sum", MODEL_PARTIAL], timeout=300).split()
        if not size.isdigit() or int(size) != pins["bytes"] or not digest or digest[0] != pins["sha256"]:
            raise RuntimeError("Transferred model failed device size/SHA256 verification; target was not replaced")
        command(remote + ["mv", MODEL_PARTIAL, MODEL_TARGET], timeout=30)
    except (RuntimeError, OSError, subprocess.TimeoutExpired):
        try:
            command(remote + ["rm", "-f", MODEL_PARTIAL], timeout=10)
        except (RuntimeError, OSError, subprocess.TimeoutExpired):
            pass  # A partial file is never treated as an installed model.
        raise


def validate_model_arguments(args, parser):
    if args.model_file and not args.visual_model:
        parser.error("--model-file requires --visual-model")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Authorized physical adb device serial")
    parser.add_argument("--run", action="store_true", help="Install demo APKs with -r and execute tests")
    parser.add_argument("--visual-model", action="store_true", help="Opt in to actual model test and pinned model (download unless --model-file is supplied)")
    parser.add_argument("--model-file", type=Path, help="Pinned local model file to verify and upload to the demo app instead of downloading")
    args = parser.parse_args()
    validate_model_arguments(args, parser)
    pins = model_pins() if args.model_file else None
    local_model = verify_model_file(args.model_file, pins) if args.model_file else None
    adb = find_tool("adb")
    listing = command([adb, "devices"])
    physical = []
    for line in listing.splitlines():
        fields = line.split()
        if len(fields) != 2 or fields[1] != "device" or fields[0].startswith("emulator-"):
            continue
        serial = fields[0]
        qemu = command([adb, "-s", serial, "shell", "getprop", "ro.kernel.qemu"]).strip()
        if qemu not in ("", "0"):
            continue
        physical.append({"serial": serial, "model": command([adb, "-s", serial, "shell", "getprop", "ro.product.model"]).strip()})
    print(json.dumps({"authorizedPhysicalDevices": physical, "preflightOnly": not args.run}, indent=2))
    if not physical:
        raise RuntimeError("No authorized physical Android device; no emulator attempted")
    if args.serial and args.serial not in [d["serial"] for d in physical]:
        raise RuntimeError("Selected serial is not an authorized physical device")
    if len(physical) > 1 and not args.serial:
        raise RuntimeError("Multiple physical devices: specify --serial explicitly")
    serial = args.serial or physical[0]["serial"]
    apks = [ROOT / "app/build/outputs/apk/demo/debug/app-demo-debug.apk",
            ROOT / "app/build/outputs/apk/androidTest/demo/debug/app-demo-debug-androidTest.apk"]
    aapt = find_tool("aapt2")
    apk_info = []
    for apk, expected in zip(apks, [PACKAGE, PACKAGE + ".test"]):
        if not apk.is_file():
            raise RuntimeError("Build current assembleDemoDebug and assembleDemoDebugAndroidTest APKs first")
        badging = command([aapt, "dump", "badging", apk])
        package = re.search(r"^package: name='([^']+)'", badging, re.M)
        if not package or package[1] != expected:
            raise RuntimeError("APK identity is not the expected demo package; refusing installation")
        if expected.endswith(".test") and f"targetPackage='{PACKAGE}'" not in badging:
            # aapt badging can omit instrumentation; inspect binary manifest instead.
            manifest = command([aapt, "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
            if not re.search(r'targetPackage[^\n]*"' + re.escape(PACKAGE) + r'"', manifest):
                raise RuntimeError("Cannot confirm test APK targets only the demo package")
        with apk.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        apk_info.append({"path": str(apk.relative_to(ROOT)), "package": expected, "sha256": digest})
    classes = ["VisualModelDeviceTest"] if args.visual_model else CLASSES
    print(json.dumps({"apks": apk_info, "classes": classes, "modelDownloadOptIn": args.visual_model and local_model is None, "verifiedLocalModel": pins}, indent=2))
    if not args.run:
        return
    output = ROOT / ".tools" / ("connected-android-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    output.mkdir(parents=True, mode=0o700)
    device = [adb, "-s", serial]
    report = {"device": next(d for d in physical if d["serial"] == serial), "apks": apk_info, "classes": classes, "passed": False}
    try:
        for i, apk in enumerate(apks):
            installed = command(device + ["install", "-r", apk], timeout=180)
            (output / f"install-{i}.txt").write_text(installed, encoding="utf-8")
            if "Success" not in installed:
                raise RuntimeError("APK install did not report Success; no uninstall attempted")
        if local_model is not None:
            upload_verified_model(device, local_model, pins)
            report["verifiedLocalModel"] = pins
        start = int(command(device + ["shell", "date", "+%s"]).strip())
        run = device + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                        ",".join("com.thotapalli.visidock." + c for c in classes),
                        "-e", "evidenceSuffix", "connected-physical"]
        if args.visual_model:
            run += ["-e", "visualModelTest", "true"]
            if local_model is None:
                run += ["-e", "downloadVisualModel", "true"]
        run += [PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
        with (output / "instrumentation.log").open("w", encoding="utf-8") as log:
            result = subprocess.run([str(v) for v in run], stdout=log, stderr=subprocess.STDOUT,
                                    timeout=2400 if args.visual_model else 1200)
        text = (output / "instrumentation.log").read_text(encoding="utf-8", errors="replace")
        # Only test-generated evidence changed during this run, never general device logs/screens.
        remote = f"/sdcard/Android/data/{PACKAGE}/files/ui-review"
        files = command(device + ["shell", "ls", "-1", remote], check=False)
        for name in files.splitlines():
            if not re.fullmatch(r"[A-Za-z0-9_.-]+\.(png|json)", name):
                continue
            modified = command(device + ["shell", "stat", "-c", "%Y", remote + "/" + name], check=False).strip()
            if modified.isdigit() and int(modified) >= start:
                evidence = output / "ui-review"
                evidence.mkdir(exist_ok=True)
                command(device + ["pull", remote + "/" + name, evidence / name], timeout=60)
        if result.returncode:
            raise RuntimeError("adb instrumentation command failed")
        report["testsPassed"] = check_result(text)
        report["passed"] = True
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        if isinstance(error, subprocess.TimeoutExpired):
            report["error"] = "Device command timed out"
            cleanup = {}
            for package in (PACKAGE, PACKAGE + ".test"):
                try:
                    command(device + ["shell", "am", "force-stop", package], timeout=10)
                    cleanup[package] = "force-stop command succeeded"
                except (RuntimeError, OSError, subprocess.TimeoutExpired):
                    cleanup[package] = "force-stop failed; test process may still be running"
            report["timeoutCleanup"] = cleanup
        else:
            report["error"] = str(error)
        raise
    finally:
        (output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"Private device evidence: {output}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(f"Validation stopped: {error}", file=sys.stderr)
        sys.exit(1)
