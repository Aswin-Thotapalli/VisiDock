import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("connected_android", ROOT / "scripts/Verify-ConnectedAndroid.py")
connected = importlib.util.module_from_spec(spec)
spec.loader.exec_module(connected)


def log(statuses, summary):
    chunks = []
    for index, code in enumerate(statuses, start=1):
        chunks.append(f"INSTRUMENTATION_STATUS: class=com.thotapalli.visidock.ExampleTest\n"
                      f"INSTRUMENTATION_STATUS: test=case{index}\n"
                      "INSTRUMENTATION_STATUS_CODE: 1\n"
                      f"INSTRUMENTATION_STATUS_CODE: {code}\n")
    return "".join(chunks) + f"INSTRUMENTATION_RESULT: stream=\n{summary}\nINSTRUMENTATION_CODE: -1\n"


class ConnectedAndroidResultTest(unittest.TestCase):
    def test_clean_two_test_pass(self):
        self.assertEqual(2, connected.check_result(log([0, 0], "Time: 1.23\n\nOK (2 tests)")))

    def test_assumption_skip_is_rejected_even_with_ok_summary(self):
        with self.assertRaisesRegex(RuntimeError, "skipped"):
            connected.check_result(log([0, -4], "OK (2 tests)"))

    def test_explicit_failure(self):
        with self.assertRaises(RuntimeError):
            connected.check_result(log([0, -2], "FAILURES!!!\nTests run: 2,  Failures: 1"))

    def test_zero_tests_is_not_a_pass(self):
        with self.assertRaisesRegex(RuntimeError, "completed-test evidence"):
            connected.check_result(log([], "OK (0 tests)"))

    def test_mismatched_status_and_final_count(self):
        with self.assertRaisesRegex(RuntimeError, "completed-test evidence"):
            connected.check_result(log([0], "OK (2 tests)"))


if __name__ == "__main__":
    unittest.main()
