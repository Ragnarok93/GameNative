import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmark.device import LabError
from benchmark.run_profiles import RUN_CONFIG_TYPES, EXTRAS, validate_run_profile
from benchmark.metrics import cadence, surface_latency, summarize
from benchmark.runner import run


def profile():
    config = {key: (False if kind is bool else {} if kind is dict else "") for key, kind in RUN_CONFIG_TYPES.items()}
    config.update(screenSize="1152x648", containerVariant="bionic", emulator="FEXCore", displayRendererMode="vulkan",
                  dxwrapperConfig="version=dxvk-test", fexcoreVersion="fex-test", graphicsDriverConfig="version=turnip-test", wineVersion="proton-test")
    extras = dict.fromkeys(EXTRAS, "false")
    extras.update(fpsLimiterEnabled="true", fpsLimiterTarget="30", lsfgMultiplier="0", lsfgFixedMultiplier="2",
        lsfgFramegenMode="fixed", lsfgAdaptiveTargetFps="60", lsfgFlowScale="0.8", lsfgFlowScaleMode="fixed",
        lsfgAdaptiveFlowPreset="balanced", lsfgPresentMode="mailbox", lsfgFrameQueueTarget="0", lsfgBackend="legacy")
    config["extraData"] = extras
    pins = {name: {"version": name + "-test", "source_sha256": "a" * 64,
        "artifacts": [{"path": "files/components/" + name, "sha256": "b" * 64}],
        "runtime_artifacts": [{"path": "native/lib" + name + ".so", "sha256": "b" * 64}]}
        for name in ("dxvk", "fex", "turnip", "proton", "lsfg")}
    variant = {"config": config, "pins": pins, "runtime_expectations": {"DXVK_LOG_LEVEL": "info"}}
    return {"schema_version": 2, "name": "mock-lab", "device": {"model": "SM-G986U1", "soc": "SM8250"},
        "game": {"source_container_id": "CUSTOM_GAME_227938910", "container_id": "BENCHMARK_FF7R"},
        "variants": {"baseline": variant, "candidate": copy.deepcopy(variant)},
        "workload": {"kind": "startup", "description": "Mock startup workload", "window_pattern": "FF7",
            "startup_timeout_ms": 30000, "warmup_ms": 1000, "measure_ms": 10000, "source_fps": 30, "trials": 3,
            "cache_modes": ["cold", "warm"], "saves": [], "setup_actions": [], "actions": []},
        "telemetry": {"layer_pattern": "SurfaceView.*BenchmarkGameActivity", "sample_interval_ms": 1000, "perfetto": False, "thermal_max_status": 1}}


class RunnerTests(unittest.TestCase):
    def test_profiles_require_full_config_real_hashes_and_matching_selections(self):
        self.assertEqual(validate_run_profile(profile())["schema_version"], 2)
        for change in (lambda p: p["variants"]["baseline"]["pins"]["fex"].update(source_sha256="0" * 64),
                       lambda p: p["variants"]["baseline"]["config"].pop("cpuList"),
                       lambda p: p["variants"]["baseline"]["config"].update(wineVersion="wrong"),
                       lambda p: p["variants"]["baseline"]["pins"]["dxvk"].update(runtime_artifacts=[])):
            value = profile(); change(value)
            with self.assertRaises(LabError): validate_run_profile(value)

    def test_scripts_reject_system_keys_unsorted_or_overlapping_inputs(self):
        for actions in ([{"type": "key", "at_ms": 0, "code": 26}],
                        [{"type": "key", "at_ms": 9000, "code": 29, "duration_ms": 2000}],
                        [{"type": "key", "at_ms": 1000, "code": 29}, {"type": "key", "at_ms": 0, "code": 30}]):
            value = profile(); value["workload"].update(kind="scripted", saves=[{"path": ".wine/drive_c/users/xuser/test.sav", "sha256": "a"*64}], actions=actions)
            with self.assertRaises(LabError): validate_run_profile(value)

    def test_surface_pending_fences_and_duplicate_samples_are_excluded(self):
        raw = "16666666\n1 0 3\n1 9223372036854775807 3\n1 1000000000 3\n1 1033333333 3\n"
        self.assertEqual(surface_latency(raw), [1000000000, 1033333333])
        values = [i * 33333333 for i in range(1, 102)]
        metrics = cadence(values + values)
        self.assertAlmostEqual(metrics["average_fps"], 30, places=4)
        self.assertEqual(metrics["frames"], 101)

    def test_measurement_excludes_warmup_and_keeps_channels_separate(self):
        value = summarize({"measurement_start_ns": 1000000000, "measurement_end_ns": 2000000000,
                           "time_to_first_content_ms": 100}, [i * 10000000 for i in range(1, 301)], [])
        self.assertEqual(value["display_cadence"]["frames"], 101)
        self.assertFalse(value["renderer_submission"]["available"])
        self.assertFalse(value["source_game_fps"]["available"])

    def test_campaign_rotates_order_and_pairs_cache_tokens(self):
        from contextlib import nullcontext
        class Fake:
            def lease(self): return nullcontext()
        calls = []
        def completed(device, value, name, index, mode, token, results):
            calls.append((name, index, mode, token))
            return {"run_id": str(len(calls)), "results": "mock", "metrics": {}}
        with tempfile.TemporaryDirectory() as tmp, patch("benchmark.runner.trial", side_effect=completed):
            report = run(Fake(), profile(), tmp)
            self.assertEqual(len(report["runs"]), 12)
            self.assertEqual([calls[i][0] for i in (0, 4, 8)], ["baseline", "candidate", "baseline"])
            for index in range(0, 12, 2):
                self.assertEqual(calls[index][2], "cold")
                self.assertEqual(calls[index+1][2], "warm")
                self.assertEqual(calls[index][3], calls[index+1][3])
            events = [json.loads(line) for line in (Path(report["results"]) / "events.jsonl").read_text().splitlines()]
            self.assertTrue(all(event["run_id"] == report["campaign_id"] for event in events))

    def test_failed_trial_stops_campaign_and_retains_partial_evidence(self):
        from contextlib import nullcontext
        class Fake:
            def lease(self): return nullcontext()
        with tempfile.TemporaryDirectory() as tmp, patch("benchmark.runner.trial", side_effect=LabError("crash")) as call:
            with self.assertRaisesRegex(LabError, "crash"): run(Fake(), profile(), tmp)
            self.assertEqual(call.call_count, 1)
            report = json.loads(next(Path(tmp).glob("*/campaign.json")).read_text())
            self.assertEqual(report["status"], "failed")


if __name__ == "__main__": unittest.main()
