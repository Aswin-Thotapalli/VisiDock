"""Read fresh test evidence; never substitute JVM parser tests for OCR/model execution."""
import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def junit(paths):
    outcomes = {}
    for path in paths:
        path = Path(path)
        files = sorted(path.rglob("*.xml")) if path.is_dir() else [path]
        for file in files:
            if not file.is_file():
                continue
            tree = ET.parse(file)
            for case in tree.iter("testcase"):
                key = case.get("classname", "").split(".")[-1] + "." + case.get("name", "")
                passed = all(case.find(tag) is None for tag in ("failure", "error", "skipped"))
                outcomes[key] = outcomes.get(key, True) and passed
    return outcomes


def evaluate(manifest, units, native, model_log):
    unit_results = {name: bool(found := [ok for key, ok in units.items() if key.startswith(name + ".")]) and all(found)
                    for name in manifest["unitClasses"]}
    native_results = {name: native.get(name, False) for name in manifest["nativeTests"]}
    model_results = {name: {"executed": False, "passed": False, "elapsedMs": None}
                     for name in manifest["modelFixtures"]}
    pattern = r"fixture=(\w+) phase=(passed|failed) elapsedMs=(\d+) recognitionSuite=(\d+)"
    for fixture, phase, elapsed, suite in re.findall(pattern, model_log):
        if fixture in model_results and int(suite) == manifest["suite"]:
            old = model_results[fixture]
            model_results[fixture] = {"executed": True,
                "passed": phase == "passed" and (old["passed"] if old["executed"] else True),
                "elapsedMs": max(int(elapsed), old["elapsedMs"] or 0)}
    correctness = all(unit_results.values()) and all(native_results.values()) and all(v["passed"] for v in model_results.values())
    latency = all(v["executed"] and v["elapsedMs"] <= manifest["scanTargetMillis"] for v in model_results.values())
    return {"suite": manifest["suite"], "correctnessPassed": correctness, "latencyTargetPassed": latency,
            "releaseGatePassed": correctness and latency, "unitContracts": unit_results,
            "nativeOcr": native_results, "actualModelFixtures": model_results,
            "scanTargetMillis": manifest["scanTargetMillis"], "limitations": manifest["limitations"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unit-results", action="append", default=[])
    parser.add_argument("--android-results", action="append", default=[])
    parser.add_argument("--model-log", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    manifest = json.loads((ROOT / "tests/vision/recognition-gate.json").read_text(encoding="utf-8"))
    model_log = args.model_log.read_text(encoding="utf-8", errors="replace") if args.model_log else ""
    report = evaluate(manifest, junit(args.unit_results), junit(args.android_results), model_log)
    report["version"] = args.version
    report["modelLogSha256"] = hashlib.sha256(model_log.encode()).hexdigest() if model_log else None
    serialized = json.dumps(report, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    return 0 if report["releaseGatePassed"] else 1


if __name__ == "__main__":
    sys.exit(main())
