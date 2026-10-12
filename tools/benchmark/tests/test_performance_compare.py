import copy
import json
import tempfile
import unittest
from pathlib import Path
from test_runner import profile
from benchmark.performance_compare import compare_performance
from benchmark.device import LabError


class ComparisonTests(unittest.TestCase):
    def campaign(self, root, name, fps=30, change=None):
        value = profile()
        if change: change(value)
        campaign = root / name
        campaign.mkdir()
        runs = []
        for index, mode in enumerate(("cold", "warm")):
            run_id = ("a" if name == "left" else "b") * 31 + str(index)
            trial = root / run_id
            trial.mkdir()
            files = {"profile.json": value, "request.json": {"run_id": run_id},
                "result.json": {"status": "complete", "run_id": run_id, "restored": True, "execution": {"harness_build": "test-build"}},
                "trial.json": {"variant": "baseline", "cache_mode": mode, "trial_index": 0},
                "device.json": {"serial": "mock", "fingerprint": "same"},
                "metrics.json": {"startup_first_content_ms": 100, "eligible_for_regression": True,
                    "display_cadence": {"available": True, "average_fps": fps, "one_percent_low_fps": fps,
                                        "p95_ms": 1000/fps, "p99_ms": 1000/fps},
                    "renderer_submission": {"available": False}}}
            for filename, data in files.items(): (trial / filename).write_text(json.dumps(data))
            (trial / "events.jsonl").write_text(json.dumps({"event": "COMPLETE"}) + "\n")
            runs.append({"run_id": run_id, "variant": "baseline"})
        (campaign / "campaign.json").write_text(json.dumps({"status": "complete", "runs": runs}))
        return campaign

    def test_metrics_report_percent_change_without_stable_promotion(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            a, b = self.campaign(root, "left"), self.campaign(root, "right", fps=27)
            report = compare_performance(a, b)
            self.assertAlmostEqual(report["cache_modes"]["cold"]["display_cadence"]["metrics"]["average_fps"]["delta_percent"], -10)
            self.assertFalse(report["stable_promotion"])
            self.assertFalse(report["cache_modes"]["cold"]["at_least_three_trials"])
            self.assertIsNone(report["source_game_fps_conclusion"])

    def test_unrelated_setting_or_workload_changes_refuse_comparison(self):
        for mutate in (lambda p: p["variants"]["baseline"]["config"].update(screenSize="1280x720"),
                       lambda p: p["workload"].update(description="different route")):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                a, b = self.campaign(root, "left"), self.campaign(root, "right", change=mutate)
                with self.assertRaises(LabError): compare_performance(a, b)

    def test_failed_host_collection_cannot_be_compared(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            a, b = self.campaign(root, "left"), self.campaign(root, "right")
            (root / ("a"*31 + "0") / "events.jsonl").write_text('{"event":"FAILED"}\n')
            with self.assertRaises(LabError): compare_performance(a, b)
