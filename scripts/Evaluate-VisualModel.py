"""Actual local image inference, not a mock. Results are synthetic-fixture evaluations only."""
import argparse, json, time, re
from pathlib import Path
import litert_lm

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--model", default=str(ROOT / ".tools/vision/gemma-4-E2B-it.litertlm"))
parser.add_argument("--vision", choices=["cpu", "gpu"], default="gpu")
parser.add_argument("--only")
parser.add_argument("--backend",choices=["cpu","gpu"],default="cpu")
args = parser.parse_args()
fixture_dir = ROOT / "tests/vision/fixtures"
cases = json.loads((fixture_dir / "cases.json").read_text())
if args.only: cases = [case for case in cases if case["id"] == args.only]
source = (ROOT / "app/src/main/java/com/thotapalli/visidock/VisualExtraction.kt").read_text(encoding="utf-8")
prompt = source.split('val instruction = """', 1)[1].split('""".trimIndent()', 1)[0]
prompt = "\n".join(line.strip() for line in prompt.strip().splitlines())
results = []
output = ROOT / "tests/vision" / ("results-" + args.vision + ("-gpu-decoder" if args.backend=="gpu" else "") + ".json")
litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)
start = time.monotonic()
with litert_lm.Engine(args.model, backend=litert_lm.Backend.GPU() if args.backend=="gpu" else litert_lm.Backend.CPU(thread_count=4),
                      vision_backend=litert_lm.Backend.GPU() if args.vision == "gpu" else litert_lm.Backend.CPU(),
                      max_num_tokens=8192, max_num_images=2) as engine:
    loaded = round(time.monotonic() - start, 2)
    print(f"Model loaded: {loaded}s", flush=True)
    for case in cases:
        start = time.monotonic()
        result = {"id": case["id"], "device": "Windows x64 development computer", "vision_backend": args.vision, "load_seconds": loaded}
        try:
            with engine.create_conversation(system_message=prompt,
                    sampler_config=litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0),
                    thinking_config=litert_lm.ThinkingConfig(enable_thinking=False), max_output_tokens=1800) as chat:
                content = []
                for i, file in enumerate(case["images"]):
                    content.extend([litert_lm.Content.Text("Front of card:" if i == 0 else "Back of the SAME card:"),
                                    litert_lm.Content.ImageFile(str(fixture_dir / file))])
                content.append(litert_lm.Content.Text("Read these photographs and return the contact JSON."))
                response = chat.send_message(litert_lm.Contents.of(*content))
                text = "".join(part.get("text", "") for part in response.get("content", []) if part.get("type") == "text")
                result["response"] = text
                clean = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
                people = json.loads(clean)["contacts"]
                errors = []
                if len(people) != len(case["people"]): errors.append("Wrong person count")
                for expected in case["people"]:
                    actual = next((p for p in people if (p.get("name") or "").casefold() == expected["name"].casefold()), None)
                    if actual is None: errors.append("Missing person: " + expected["name"]); continue
                    for field, value in expected.items():
                        normal = lambda v: re.sub(r"\s+", " ", v or "").strip().casefold()
                        if normal(actual.get(field)) != normal(value): errors.append(expected["name"] + ": " + field)
                result["errors"] = errors
                result["passed"] = not errors
        except Exception as error:
            result.update(passed=False, error=str(error))
        result["seconds"] = round(time.monotonic() - start, 2)
        results.append(result)
        output.write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(json.dumps(result), flush=True)
