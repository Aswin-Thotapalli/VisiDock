import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("recognition_gate", ROOT / "scripts/Check-RecognitionGate.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class RecognitionGateTest(unittest.TestCase):
    manifest = {"suite": 2, "unitClasses": ["ParserTest"], "nativeTests": ["OcrTest.smallPrint"],
                "modelFixtures": {"card": []}, "scanTargetMillis": 15000, "limitations": ["Synthetic"]}

    def test_parser_success_never_implies_model_execution(self):
        report = gate.evaluate(self.manifest, {"ParserTest.parse": True}, {"OcrTest.smallPrint": True}, "")
        self.assertFalse(report["correctnessPassed"])
        self.assertFalse(report["actualModelFixtures"]["card"]["executed"])

    def test_old_suite_results_do_not_validate_new_assignment_pipeline(self):
        report = gate.evaluate(self.manifest, {"ParserTest.parse": True}, {"OcrTest.smallPrint": True},
                               "fixture=card phase=passed elapsedMs=1000 recognitionSuite=1")
        self.assertFalse(report["releaseGatePassed"])

    def test_correct_but_slow_scan_does_not_pass_latency_gate(self):
        report = gate.evaluate(self.manifest, {"ParserTest.parse": True}, {"OcrTest.smallPrint": True},
                               "fixture=card phase=passed elapsedMs=50000 recognitionSuite=2")
        self.assertTrue(report["correctnessPassed"])
        self.assertFalse(report["latencyTargetPassed"])

    def test_skipped_device_test_is_not_passing_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "result.xml"
            file.write_text('<testsuite><testcase classname="sample.OcrTest" name="smallPrint"><skipped/></testcase></testsuite>')
            self.assertEqual({"OcrTest.smallPrint": False}, gate.junit([file]))

    def test_all_three_layers_and_latency_required(self):
        report = gate.evaluate(self.manifest, {"ParserTest.parse": True}, {"OcrTest.smallPrint": True},
                               "fixture=card phase=passed elapsedMs=12000 recognitionSuite=2")
        self.assertTrue(report["releaseGatePassed"])


if __name__ == "__main__":
    unittest.main()
