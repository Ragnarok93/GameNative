"""Read-only, capability-aware device telemetry during an owned benchmark."""
import json
import re
import shlex
from .device import LabError, PACKAGE
from .metrics import surface_latency


class Collectors:
    def __init__(self, device, telemetry, options):
        self.device, self.telemetry, self.options = device, telemetry, options
        self.presented = set()
        self.layer = None
        self.unavailable = {}
        self.cpu_previous = None
        self.previous_frames = set()
        self.coverage_gaps = False

    def read(self, name, *args):
        try:
            return self.device.call(*args, timeout=10)
        except LabError as exc:
            self.unavailable[name] = str(exc)
            return None

    def sample(self):
        sample = {}
        layers = self.read("surface_layers", "shell", "dumpsys", "SurfaceFlinger", "--list")
        if layers is not None:
            matches = [line for line in layers.splitlines() if re.search(self.options["layer_pattern"], line)]
            if len(matches) == 1:
                if self.layer is not None and self.layer != matches[0]:
                    raise LabError("Presentation layer changed during measurement")
                self.layer = matches[0]
                raw = self.read("surface_latency", "shell", "dumpsys", "SurfaceFlinger", "--latency", shlex.quote(self.layer))
                if raw is not None:
                    frames = surface_latency(raw)
                    if frames and self.previous_frames and not self.previous_frames.intersection(frames):
                        self.coverage_gaps = True
                    if frames:
                        self.previous_frames = set(frames)
                    self.presented.update(frames)
                    sample["presented_timestamps_ns"] = frames
            else:
                self.unavailable["surface_latency"] = f"Expected one matching layer; found {len(matches)}"
        raw_cpu = self.read("cpu", "shell", "cat", "/proc/stat")
        if raw_cpu:
            row = raw_cpu.splitlines()[0].split()
            if row[0] == "cpu" and len(row) >= 9:
                values = list(map(int, row[1:9]))  # guest counters already included in user/nice
                total, idle = sum(values), values[3] + values[4]
                if self.cpu_previous:
                    dt, di = total - self.cpu_previous[0], idle - self.cpu_previous[1]
                    if dt > 0:
                        sample["system_cpu_busy_percent"] = 100 * (dt - di) / dt
                self.cpu_previous = total, idle
        for name, path in [("gpu_busy_raw", "/sys/class/kgsl/kgsl-3d0/gpubusy"),
                           ("gpu_frequency_raw", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")]:
            value = self.read(name, "shell", "cat", path)
            if value is not None:
                sample[name] = value
        thermal = self.read("thermal", "shell", "dumpsys", "thermalservice")
        if thermal:
            match = re.search(r"Thermal Status:\s*(\d+)", thermal, re.I)
            if match:
                sample["thermal_status"] = int(match[1])
            sample["thermal_raw"] = thermal
        battery = self.read("battery", "shell", "dumpsys", "battery")
        if battery:
            match = re.search(r"temperature:\s*(-?\d+)", battery)
            if match:
                sample["battery_temperature_c"] = int(match[1]) / 10
        memory = self.read("memory", "shell", "dumpsys", "meminfo", PACKAGE)
        if memory:
            match = re.search(r"TOTAL PSS:\s*(\d+)", memory)
            if match:
                sample["android_app_pss_kb"] = int(match[1])
        sample["processes_raw"] = self.read("processes", "shell", "ps", "-A", "-o", "UID,PID,NAME,RSS,TIME")
        sample["cpu_frequencies_raw"] = self.read("cpu_frequencies", "shell", "sh", "-c",
            """'for p in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do echo "$p"; cat "$p"; done'""")
        self.telemetry.event("device_sample", **sample)
        return sample

    def finish(self):
        self.telemetry.artifact("presentation.json", {"layer": self.layer, "timestamps_ns": sorted(self.presented),
            "ring_coverage_gaps_detected": self.coverage_gaps,
            "unavailable": self.unavailable, "sampling_interval_ms": self.options["sample_interval_ms"],
            "completeness": "SurfaceFlinger finite ring sampled over ADB; coverage is not guaranteed"})


def thermal_gate(device, maximum):
    raw = device.call("shell", "dumpsys", "thermalservice")
    match = re.search(r"Thermal Status:\s*(\d+)", raw, re.I)
    if not match:
        raise LabError("Thermal status unavailable: comparability gate cannot be checked")
    if int(match[1]) > maximum:
        raise LabError("Device thermal status exceeds profile gate; cool the device before running")
    return int(match[1])
