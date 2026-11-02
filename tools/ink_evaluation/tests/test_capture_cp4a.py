"""Pure Python parser regression tests; no Android device needed."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "capture_cp4a.py"
spec = importlib.util.spec_from_file_location("capture_cp4a", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Cp4aMetricsTests(unittest.TestCase):
    def test_total_pss_named_summary(self):
        self.assertEqual(76900, module.parse_total_pss_kb(" TOTAL PSS: 76,900 TOTAL RSS: 234"))

    def test_total_pss_table(self):
        self.assertEqual(1234, module.parse_total_pss_kb(" TOTAL      1234     2    3    4"))

    def test_missing_process_does_not_invent_memory(self):
        self.assertIsNone(module.parse_total_pss_kb("No process found for com.notebook.app"))

    def test_framestats_valid_and_invalid_flags(self):
        text = """other\n---PROFILEDATA---\nFLAGS,INTENDED_VSYNC,FRAME_COMPLETED\n0,100000000,110000000\n1,200000000,220000000\n0,300000000,335000000\n---PROFILEDATA---\n"""
        self.assertEqual([10.0, 35.0], module.parse_framestats_ms(text))

    def test_framestats_no_data(self):
        self.assertEqual([], module.parse_framestats_ms("Status: no profile data"))

    def test_framestats_realistic_header_order(self):
        text = """---PROFILEDATA---\nFlags,IntendedVsync,Vsync,DrawStart,FrameCompleted\n0,1000000,1000010,1004000,5500000\n---PROFILEDATA---"""
        self.assertEqual([4.5], module.parse_framestats_ms(text))

    def test_percentile_safe_when_empty(self):
        self.assertIsNone(module.percentile([], 95))

    def test_percentile_median(self):
        self.assertEqual(20.0, module.percentile([10.0, 20.0, 30.0], 50))

    def test_report_flags_app_restart(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            module.write_json(root / "session.json", {"serial": "emulator-5554"})
            for label, pid in [("baseline", "111"), ("final", "222")]:
                snap = root / "snapshots" / label
                snap.mkdir(parents=True)
                module.write_json(snap / "snapshot.json", {"label": label, "pid": pid, "utc": "example"})
                (snap / "meminfo.txt").write_text("TOTAL PSS: 3000\n")
                (snap / "gfxinfo.txt").write_text("---PROFILEDATA---\nFLAGS,INTENDED_VSYNC,FRAME_COMPLETED\n0,100000000,110000000\n---PROFILEDATA---")
            report = module.collect_report(root)
            self.assertTrue(report["process_identity_changed"])
            self.assertFalse(report["process_missing_at_snapshot"])
            self.assertEqual(2, len(report["snapshots"]))
            self.assertEqual(3000, report["snapshots"][0]["total_pss_kb"])

    def test_cli_start_sample_finish_with_mocked_adb(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work) / "run-01"
            def mock_adb(_adb, _serial, *args, **_kwargs):
                command = " ".join(args)
                if "meminfo" in command:
                    return "TOTAL PSS: 75000"
                if "gfxinfo" in command and "framestats" in command:
                    return "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted\n0,10000000,30000000\n---PROFILEDATA---"
                if "getprop" in command:
                    return "test-property"
                return ""
            with patch.object(module, "find_adb", return_value="adb"), \
                 patch.object(module, "select_device", return_value="emulator-5554"), \
                 patch.object(module, "pid_for", return_value="1234"), \
                 patch.object(module, "run_adb", side_effect=mock_adb):
                self.assertEqual(0, module.main(["start", "--output", str(root)]))
                self.assertEqual(0, module.main(["sample", "--output", str(root), "--label", "after_draw"]))
                self.assertEqual(0, module.main(["finish", "--output", str(root)]))
                self.assertTrue((root / "summary.csv").is_file())
                report = module.read_json(root / "summary.json")
                self.assertEqual(["baseline", "after_draw", "final"], [v["label"] for v in report["snapshots"]])
                self.assertEqual(75000, report["snapshots"][1]["total_pss_kb"])

    def test_invalid_snapshot_label_fails_before_writing(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            with self.assertRaises(ValueError):
                module.snapshot("adb", "emulator-5554", root, "../bad")


if __name__ == "__main__":
    unittest.main()
