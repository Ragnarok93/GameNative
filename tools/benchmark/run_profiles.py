"""Version 2 profiles: complete configuration, binary pins and timed workloads."""
import json
import re
from pathlib import Path
from .device import LabError
from .profiles import CONFIG_TYPES

RUN_CONFIG_TYPES = dict(CONFIG_TYPES, containerVariant=str, displayRendererMode=str,
    execArgs=str, suspendPolicy=str, micEnabled=bool, launchRealSteam=bool, launchBionicSteam=bool,
    extraData=dict)
EXTRAS = {"fpsLimiterEnabled", "fpsLimiterTarget", "lsfgEnabled", "lsfgMultiplier", "lsfgFixedMultiplier",
    "lsfgFramegenMode", "lsfgAdaptiveTargetFps", "lsfgFlowScale", "lsfgFlowScaleMode",
    "lsfgAdaptiveFlowPreset", "lsfgPerformanceMode", "lsfgPresentMode", "lsfgFrameQueueEnabled",
    "lsfgFrameQueueTarget", "lsfgBackend"}


def require(condition, message):
    if not condition:
        raise LabError(message)


def sha(value):
    return isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value) and value != "0" * 64


def validate_run_profile(profile):
    require(set(profile) == {"schema_version", "name", "device", "game", "variants", "workload", "telemetry"}, "Invalid run profile fields")
    require(type(profile["schema_version"]) is int and profile["schema_version"] == 2, "Run requires schema version 2")
    require(isinstance(profile["name"], str) and bool(profile["name"]), "Profile name required")
    require(set(profile["device"]) == {"model", "soc"} and all(isinstance(v, str) and v for v in profile["device"].values()), "Device model/SoC required")
    game = profile["game"]
    require(set(game) == {"source_container_id", "container_id"}, "Invalid game identity")
    require(isinstance(game["source_container_id"], str) and re.fullmatch(r"(?:STEAM|CUSTOM_GAME)_[0-9]+", game["source_container_id"]), "Invalid source game")
    require(isinstance(game["container_id"], str) and re.fullmatch(r"BENCHMARK_[A-Za-z0-9_-]+", game["container_id"]), "Dedicated sandbox required")
    variants = profile["variants"]
    require(isinstance(variants, dict) and 1 <= len(variants) <= 16, "1-16 variants required")
    for name, variant in variants.items():
        require(re.fullmatch(r"[A-Za-z0-9_-]+", name), "Invalid variant name")
        require(set(variant) == {"pins", "config", "runtime_expectations"}, "Invalid variant fields")
        require(set(variant["pins"]) == {"dxvk", "fex", "turnip", "proton", "lsfg"}, "All five component pins required")
        for component, pin in variant["pins"].items():
            require(set(pin) == {"version", "source_sha256", "artifacts", "runtime_artifacts"}, f"Invalid {component} pin")
            require(isinstance(pin["version"], str) and pin["version"] and sha(pin["source_sha256"]), f"Verified {component} provenance required")
            require(isinstance(pin["artifacts"], list) and 1 <= len(pin["artifacts"]) <= 24, "Binary artifacts required")
            require(isinstance(pin["runtime_artifacts"], list) and 1 <= len(pin["runtime_artifacts"]) <= 24, "Runtime binary readback pins required")
            for artifact in pin["artifacts"] + pin["runtime_artifacts"]:
                require(set(artifact) == {"path", "sha256"} and sha(artifact["sha256"]), "Invalid binary pin")
                path = artifact["path"]
                require(isinstance(path, str) and path.startswith(("files/", "native/")) and ".." not in path.split('/') and "\\" not in path, "Unsafe artifact path")
        config = variant["config"]
        require(set(config) == set(RUN_CONFIG_TYPES), "Run configuration must explicitly resolve every supported setting")
        for key, kind in RUN_CONFIG_TYPES.items():
            require(type(config[key]) is kind, f"Wrong type for {key}")
        require(re.fullmatch(r"[1-9][0-9]{1,3}x[1-9][0-9]{1,3}", config["screenSize"]), "Invalid resolution")
        require(config["containerVariant"] == "bionic" and config["emulator"] == "FEXCore", "Runtime observation currently supports bionic/FEXCore")
        require(config["displayRendererMode"] == "vulkan", "Vulkan renderer required for source-cap readback")
        selections = {"dxvk": dict(part.split("=", 1) for part in config["dxwrapperConfig"].split(',') if '=' in part).get("version"),
            "fex": config["fexcoreVersion"], "turnip": dict(part.split("=", 1) for part in config["graphicsDriverConfig"].split(',') if '=' in part).get("version"),
            "proton": config["wineVersion"]}
        require(all(selections[key] == variant["pins"][key]["version"] for key in selections), "Component selections do not match pins")
        require(not config["micEnabled"] and not config["launchRealSteam"] and not config["launchBionicSteam"], "Lab runs require offline guest launch without microphone/native Steam")
        extras = config["extraData"]
        require(set(extras) == EXTRAS and all(type(v) is str for v in extras.values()), "Explicit canonical FPS/LSFG extras required")
        for key in ("fpsLimiterEnabled", "lsfgEnabled", "lsfgPerformanceMode", "lsfgFrameQueueEnabled"):
            require(extras[key] in {"true", "false"}, f"Invalid {key}")
        require(extras["fpsLimiterEnabled"] == "true", "Benchmark source cap must be enabled")
        for key, minimum, maximum in [("fpsLimiterTarget", 5, 240), ("lsfgMultiplier", 0, 4), ("lsfgFixedMultiplier", 2, 4), ("lsfgAdaptiveTargetFps", 5, 240), ("lsfgFrameQueueTarget", 0, 2)]:
            require(extras[key].isdigit() and minimum <= int(extras[key]) <= maximum, f"Invalid {key}")
        require(extras["lsfgFramegenMode"] in {"fixed", "adaptive"} and extras["lsfgFlowScaleMode"] in {"fixed", "adaptive"}, "Invalid LSFG mode")
        require(extras["lsfgAdaptiveFlowPreset"] in {"quality", "balanced", "low", "auto"}, "Invalid adaptive preset")
        require(extras["lsfgPresentMode"] in {"fifo", "mailbox", "immediate"} and extras["lsfgBackend"] in {"legacy", "native"}, "Invalid LSFG backend/present mode")
        require(.25 <= float(extras["lsfgFlowScale"]) <= 1, "Invalid flow scale")
        expected = variant["runtime_expectations"]
        require(isinstance(expected, dict) and expected and all(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", k) and isinstance(v, str) for k, v in expected.items()), "Runtime environment assertions required")
    workload = profile["workload"]
    require(set(workload) == {"kind", "description", "window_pattern", "startup_timeout_ms", "warmup_ms", "measure_ms", "source_fps", "trials", "cache_modes", "saves", "setup_actions", "actions"}, "Invalid workload fields")
    require(workload["kind"] in {"startup", "scripted", "exploration"} and isinstance(workload["description"], str) and workload["description"], "Workload required")
    require(isinstance(workload["window_pattern"], str) and 1 <= len(workload["window_pattern"]) <= 128, "Explicit readiness window pattern required")
    re.compile(workload["window_pattern"])
    for key, lo, hi in [("startup_timeout_ms", 1000, 300000), ("warmup_ms", 0, 120000), ("measure_ms", 1000, 600000), ("source_fps", 5, 240), ("trials", 1, 10)]:
        require(type(workload[key]) is int and lo <= workload[key] <= hi, f"Invalid {key}")
    require(workload["cache_modes"] == ["cold", "warm"], "Paired cold/warm shader-cache trials required")
    for variant in variants.values():
        require(int(variant["config"]["extraData"]["fpsLimiterTarget"]) == workload["source_fps"], "Source cap differs from workload")
    require(isinstance(workload["saves"], list) and len(workload["saves"]) <= 32, "Invalid save list")
    for save in workload["saves"]:
        require(set(save) == {"path", "sha256"} and sha(save["sha256"]) and isinstance(save["path"], str) and save["path"].startswith(".wine/drive_c/users/") and ".." not in save["path"].split('/'), "Invalid pinned save")
    for key, limit in [("setup_actions", workload["warmup_ms"]), ("actions", workload["measure_ms"])]:
        actions = workload[key]
        require(isinstance(actions, list) and len(actions) <= 1000, "Invalid input script")
        previous = -1
        for action in actions:
            require(type(action.get("at_ms")) is int and previous <= action["at_ms"] <= limit, "Input times must be ordered within their phase")
            previous = action["at_ms"]
            if action.get("type") == "key":
                require(set(action) in ({"type", "at_ms", "code"}, {"type", "at_ms", "code", "duration_ms"}) and type(action["code"]) is int, "Invalid key input")
                code = action["code"]
                require(code in set(range(7, 17)) | set(range(19, 23)) | set(range(29, 63)) | {66} | set(range(96, 115)) | set(range(131, 143)), "System keys cannot be injected")
                duration = action.get("duration_ms", 0)
                require(type(duration) is int and 0 <= duration <= 10000 and action["at_ms"] + duration <= limit, "Invalid key hold duration")
                previous += duration
            elif action.get("type") == "tap":
                require(set(action) == {"type", "at_ms", "x", "y"} and all(type(action[v]) in (int, float) and 0 <= action[v] < 8192 for v in ("x", "y")), "Invalid tap input")
            elif action.get("type") == "look":
                require(set(action) == {"type", "at_ms", "dx", "dy", "duration_ms"} and all(type(action[v]) is int and -8192 <= action[v] <= 8192 for v in ("dx", "dy")), "Invalid camera movement")
                require(type(action["duration_ms"]) is int and 16 <= action["duration_ms"] <= 10000 and action["at_ms"] + action["duration_ms"] <= limit, "Invalid camera duration")
                previous += action["duration_ms"]
            else:
                raise LabError("Unsupported input action")
    if workload["kind"] == "startup":
        require(not workload["saves"] and not workload["actions"] and not workload["setup_actions"], "Startup workloads cannot claim gameplay input/save scenarios")
    elif workload["kind"] == "scripted":
        require(workload["saves"] and (workload["actions"] or workload["setup_actions"]), "Scripted workloads require pinned save and input route")
    telemetry = profile["telemetry"]
    require(set(telemetry) == {"layer_pattern", "sample_interval_ms", "perfetto", "thermal_max_status"}, "Invalid telemetry fields")
    require(type(telemetry["perfetto"]) is bool and type(telemetry["sample_interval_ms"]) is int and 250 <= telemetry["sample_interval_ms"] <= 1000, "Invalid telemetry options")
    require(type(telemetry["thermal_max_status"]) is int and 0 <= telemetry["thermal_max_status"] <= 3, "Invalid thermal gate")
    require(isinstance(telemetry["layer_pattern"], str) and 1 <= len(telemetry["layer_pattern"]) <= 128, "Surface layer selection required")
    re.compile(telemetry["layer_pattern"])
    return profile


def load_run_profile(path):
    return validate_run_profile(json.loads(Path(path).read_text()))
