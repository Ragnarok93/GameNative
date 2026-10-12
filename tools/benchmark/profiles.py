import json
import re
from pathlib import Path
from .device import LabError

CONFIG_TYPES = {
    "screenSize": str, "envVars": str, "cpuList": str, "cpuListWoW64": str,
    "graphicsDriver": str, "graphicsDriverVersion": str,
    "graphicsDriverConfig": str, "rendererPresentMode": str,
    "dxwrapper": str, "dxwrapperConfig": str, "emulator": str,
    "fexcoreVersion": str, "fexcorePreset": str, "wineVersion": str,
    "showFPS": bool, "wow64Mode": bool,
}


def load_profile(path):
    profile = json.loads(Path(path).read_text())
    expected = {"schema_version", "name", "device", "game", "pins", "config", "workload"}
    if set(profile) != expected or type(profile["schema_version"]) is not int or profile["schema_version"] != 1:
        raise LabError("Unsupported profile schema or unknown/missing fields")
    if not isinstance(profile["name"], str) or not profile["name"]:
        raise LabError("Profile name is required")
    if set(profile["device"]) != {"model", "soc"}:
        raise LabError("Device requires model and soc")
    if not all(isinstance(v, str) and v for v in profile["device"].values()):
        raise LabError("Device identifiers must be nonempty strings")
    if set(profile["game"]) != {"source", "app_id", "container_id"}:
        raise LabError("Game requires source, app_id, container_id")
    if profile["game"]["source"] not in {"STEAM", "CUSTOM_GAME"} or type(profile["game"]["app_id"]) is not int or profile["game"]["app_id"] <= 0:
        raise LabError("Phase 1 profiles require a positive STEAM or CUSTOM_GAME ID")
    # Phase 1 does not touch the active game container. Prepare a dedicated sandbox.
    if not re.fullmatch(r"BENCHMARK_[A-Za-z0-9_-]+", profile["game"]["container_id"]):
        raise LabError("A dedicated BENCHMARK_ container is required")
    if set(profile["pins"]) != {"dxvk", "fex", "turnip", "proton", "lsfg"}:
        raise LabError("All component pins are required")
    for component, pin in profile["pins"].items():
        if set(pin) != {"version", "source_sha256"} or not isinstance(pin["version"], str) or not pin["version"]:
            raise LabError(f"Invalid {component} pin")
        if not isinstance(pin["source_sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", pin["source_sha256"]) or pin["source_sha256"] == "0" * 64:
            raise LabError(f"Replace the example {component} source SHA-256 before executing")
    config = profile["config"]
    if not isinstance(config, dict) or not config or set(config) - CONFIG_TYPES.keys():
        raise LabError("Unsupported or empty configuration patch")
    for key, value in config.items():
        if type(value) is not CONFIG_TYPES[key]:
            raise LabError(f"Wrong type for {key}")
    if "screenSize" in config and not re.fullmatch(r"[1-9][0-9]{1,3}x[1-9][0-9]{1,3}", config["screenSize"]):
        raise LabError("Invalid resolution")
    workload = profile["workload"]
    if set(workload) != {"description", "source_fps", "trials", "cache_modes"} or not isinstance(workload["description"], str) or not workload["description"]:
        raise LabError("A repeatable workload description is required")
    if type(workload["trials"]) is not int or workload["trials"] < 1 or type(workload["source_fps"]) is not int or workload["source_fps"] <= 0:
        raise LabError("Trials and source_fps must be positive integers")
    if workload["cache_modes"] != ["cold", "warm"]:
        raise LabError("Record separate cold and warm trials")
    return profile
