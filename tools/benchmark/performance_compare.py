"""Compare only completed trials with matching device, workload and controls."""
import json
import statistics
import re
from pathlib import Path
from .device import LabError


def read_trial(path):
    root = Path(path)
    def read(name):
        return json.loads((root / name).read_text())
    request, result = read("request.json"), read("result.json")
    events = [json.loads(line) for line in (root / "events.jsonl").read_text().splitlines()]
    if result.get("status") != "complete" or result.get("run_id") != request.get("run_id") or not result.get("restored"):
        raise LabError("Trial did not complete and restore configuration")
    if not events or events[-1].get("event") != "COMPLETE" or any(e.get("event") == "FAILED" for e in events):
        raise LabError("Host collection did not complete")
    return {"request": request, "profile": read("profile.json"), "result": result,
            "trial": read("trial.json"), "device": read("device.json"), "metrics": read("metrics.json")}


def trials(path, variant=None):
    root = Path(path)
    if not (root / "campaign.json").exists():
        selected = [read_trial(root)]
    else:
        campaign = json.loads((root / "campaign.json").read_text())
        if campaign.get("status") != "complete":
            raise LabError("Partial or failed campaigns are not comparable")
        selected = []
        for run in campaign["runs"]:
            # Resolve portable sibling directories from run ID, not an untrusted
            # results path embedded in imported evidence.
            if variant is None or run["variant"] == variant:
                if not re.fullmatch(r"[a-f0-9]{32}", run["run_id"]):
                    raise LabError("Invalid campaign run ID")
                selected.append(read_trial(root.parent / run["run_id"]))
    if not selected or len({t["trial"]["variant"] for t in selected}) != 1:
        raise LabError("Choose exactly one variant from each campaign")
    return selected


def controls(trial, component):
    profile = trial["profile"]
    variant = profile["variants"][trial["trial"]["variant"]]
    config = dict(variant["config"])
    allowed = {"dxvk": {"dxwrapperConfig"}, "fex": {"fexcoreVersion", "fexcorePreset"},
               "turnip": {"graphicsDriverConfig", "graphicsDriverVersion"}, "proton": {"wineVersion"},
               "lsfg": set()}[component]
    for key in allowed:
        config.pop(key)
    if component == "lsfg":
        config["extraData"] = {k: v for k, v in config["extraData"].items() if not k.startswith("lsfg")}
    pins = {k: v for k, v in variant["pins"].items() if k != component}
    volatile = {"GAMENATIVE_BENCHMARK_RUN_ID", "DXVK_STATE_CACHE_PATH", "MESA_SHADER_CACHE_DIR", "DXVK_LOG_PATH", "EVSHIM_SHM_ID", "GAMEPAD_SHM_ID"}
    prefixes = {"dxvk": ("DXVK_",), "fex": ("FEX_",), "turnip": ("TU_", "WRAPPER_CUSTOM_DRIVER"),
                "proton": ("WINE",), "lsfg": ("LSFG_", "WRAPPER_LSFG_", "VK_INSTANCE_LAYERS", "VK_LOADER_LAYERS_ENABLE")}[component]
    def fixed_environment(values):
        return {key: value for key, value in values.items() if key not in volatile and not key.startswith(prefixes)}
    actual = trial["result"]["execution"].get("runtime_environment")
    if not isinstance(actual, dict):
        raise LabError("Missing actual runtime environment readback")
    return {"device": trial["device"], "game": profile["game"], "workload": profile["workload"],
            "telemetry": profile["telemetry"], "config": config, "fixed_pins": pins,
            "runtime_expectations": fixed_environment(variant["runtime_expectations"]),
            "actual_fixed_environment": fixed_environment(actual),
            "harness_build": trial["result"]["execution"]["harness_build"] if component != "lsfg" else "LSFG build varied"}


def compare_performance(baseline, candidate, component="dxvk", baseline_variant=None, candidate_variant=None):
    if component not in {"dxvk", "fex", "turnip", "proton", "lsfg"}:
        raise LabError("Choose one varied component")
    left, right = trials(baseline, baseline_variant), trials(candidate, candidate_variant)
    if any(t["profile"]["workload"]["kind"] == "exploration" for t in left + right):
        raise LabError("Exploration sessions cannot establish regressions; freeze and validate a replay route")
    if any(t["metrics"].get("eligible_for_regression") is not True for t in left + right):
        raise LabError("Run provenance/workload is not eligible for regression analysis")
    fixed = controls(left[0], component)
    if any(controls(t, component) != fixed for t in left + right):
        raise LabError("Device, workload, telemetry or non-varied component/config controls differ")
    groups = {}
    for mode in ("cold", "warm"):
        a, b = [t for t in left if t["trial"]["cache_mode"] == mode], [t for t in right if t["trial"]["cache_mode"] == mode]
        if len(a) != len(b) or not a:
            raise LabError("Matching numbers of cold and warm trials required")
        if len({t["trial"]["trial_index"] for t in a}) != len(a) or len({t["trial"]["trial_index"] for t in b}) != len(b):
            raise LabError("Duplicate trial indices")
        differences = {}
        for channel in ("display_cadence", "renderer_submission"):
            if not all(t["metrics"][channel]["available"] for t in a + b):
                differences[channel] = {"available": False, "reason": "Metric unavailable in one or more trials"}
                continue
            values = {}
            for metric in ("average_fps", "one_percent_low_fps", "p95_ms", "p99_ms"):
                x = [t["metrics"][channel][metric] for t in a]
                y = [t["metrics"][channel][metric] for t in b]
                mx, my = statistics.median(x), statistics.median(y)
                values[metric] = {"baseline_median": mx, "candidate_median": my,
                    "delta_percent": 100 * (my - mx) / mx if mx else None,
                    "baseline_range": [min(x), max(x)], "candidate_range": [min(y), max(y)]}
            differences[channel] = {"available": True, "metrics": values}
        startup_a = statistics.median(t["metrics"]["startup_first_content_ms"] for t in a)
        startup_b = statistics.median(t["metrics"]["startup_first_content_ms"] for t in b)
        groups[mode] = {"trials_per_variant": len(a), "at_least_three_trials": len(a) >= 3,
            "startup_first_content_ms": {"baseline": startup_a, "candidate": startup_b}, **differences}
        signals = []
        display = differences["display_cadence"]
        if display["available"]:
            if display["metrics"]["average_fps"]["delta_percent"] < -5:
                signals.append("Display cadence median fell more than 5%")
            if display["metrics"]["p99_ms"]["delta_percent"] > 10:
                signals.append("P99 display gap median increased more than 10%")
        groups[mode]["regression_signals"] = signals
        groups[mode]["assessment"] = "insufficient_trials" if len(a) < 3 else "suspected_regression" if signals else "no_threshold_regression_observed" if display["available"] else "inconclusive"
    return {"schema_version": 2, "varied_component": component, "cache_modes": groups,
        "workload_kind": fixed["workload"]["kind"], "stable_promotion": False,
        "source_game_fps_conclusion": None,
        "limitations": ["Display cadence includes synthetic frames; renderer callbacks are submissions",
                        "Startup-only trials do not establish gameplay performance",
                        "Inspect thermal/frequency evidence and sampled ring coverage before drawing conclusions"]}
