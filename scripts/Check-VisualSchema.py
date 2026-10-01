"""Compile a JSON Schema with the host llguidance version pinned by LiteRT-LM 0.17.1.

This preflight runs no model and does not establish Android compatibility or accuracy.
"""
import argparse
import hashlib
import importlib.metadata
import json
import sys
from pathlib import Path

PINNED_VERSION = "1.3.0"
LIMITATIONS = [
    "Host wheel omits LiteRT-specific patches and does not use the Android tokenizer.",
    "A host grammar pass is not Android/model execution evidence.",
    "Grammar constraints do not prove OCR accuracy, contact ownership or scan speed.",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("schema", type=Path, help="JSON exported from the current VisualSchema.json")
    parser.add_argument("--output", type=Path, help="Optional JSON report path")
    args = parser.parse_args()
    report = {"requiredVersion": PINNED_VERSION, "compilePassed": False,
              "negativeControlPassed": False, "limitations": LIMITATIONS}
    exit_code = 2
    try:
        installed = importlib.metadata.version("llguidance")
        report["installedVersion"] = installed
        if installed != PINNED_VERSION:
            raise ValueError(f"Use an isolated environment with llguidance=={PINNED_VERSION}; found {installed}.")
        import llguidance

        raw = args.schema.read_bytes()
        if len(raw) > 1_000_000:
            raise ValueError("Schema exceeds this preflight's 1 MB input limit.")
        schema = json.loads(raw.decode("utf-8-sig"))
        if not isinstance(schema, dict):
            raise ValueError("VisiDock expects a JSON object schema.")
        report["schemaSha256"] = hashlib.sha256(raw).hexdigest()
        report["schemaBytes"] = len(raw)
        # An empty error string means the native host grammar compiler accepted it.
        error = llguidance.LLMatcher.validate_grammar(
            llguidance.LLMatcher.grammar_from_json_schema(json.dumps(schema)))
        report["compileError"] = error
        report["compilePassed"] = error == ""
        # Detect accidental lenient validation or a changed compiler contract.
        # This keyword caused the real Android pre-generation failure.
        negative = dict(schema)
        negative["propertyNames"] = {"type": "string"}
        negative_error = llguidance.LLMatcher.validate_grammar(
            llguidance.LLMatcher.grammar_from_json_schema(json.dumps(negative)))
        report["negativeControlError"] = negative_error
        report["negativeControlPassed"] = "propertyNames" in negative_error
        exit_code = 0 if report["compilePassed"] and report["negativeControlPassed"] else 1
    except importlib.metadata.PackageNotFoundError:
        report["error"] = f"Install llguidance=={PINNED_VERSION} in an isolated validation environment."
    except (OSError, ValueError, ImportError, RuntimeError) as error:
        report["error"] = str(error)
    serialized = json.dumps(report, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
