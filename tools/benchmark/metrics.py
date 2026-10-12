"""Keep presentation, renderer submission and source-game metrics separate."""
import math
import statistics
from .device import LabError


def cadence(timestamps_ns):
    if any(type(value) is not int or value <= 0 for value in timestamps_ns):
        raise LabError("Frame timestamps must be positive integer nanoseconds")
    values = sorted(set(timestamps_ns))
    gaps = [(b - a) / 1e6 for a, b in zip(values, values[1:])]
    if len(gaps) < 2:
        return {"available": False, "reason": "Insufficient timestamped frames"}
    def percentile(q):
        position = (len(gaps) - 1) * q
        ordered = sorted(gaps)
        lower = math.floor(position)
        return ordered[lower] + (ordered[math.ceil(position)] - ordered[lower]) * (position - lower)
    slow = sorted(gaps, reverse=True)[:max(1, math.ceil(len(gaps) * .01))]
    return {"available": True, "frames": len(values), "average_fps": 1000 / statistics.mean(gaps),
            "one_percent_low_fps": 1000 / statistics.mean(slow), "p95_ms": percentile(.95),
            "p99_ms": percentile(.99), "maximum_gap_ms": max(gaps),
            "definition": "1% low = inverse mean of slowest ceil(1%) inter-frame gaps"}


def surface_latency(text):
    # SurfaceFlinger --latency triples: desiredPresent, actualPresent, frameReady.
    # INT64_MAX represents a pending fence, zero an empty ring entry.
    frames = []
    for line in text.splitlines()[1:]:
        fields = line.split()
        if len(fields) != 3 or not all(value.isdigit() for value in fields):
            continue
        actual = int(fields[1])
        if 0 < actual < 2**63 - 1:
            frames.append(actual)
    return frames


def summarize(execution, presented, submitted):
    start, end = execution["measurement_start_ns"], execution["measurement_end_ns"]
    if type(start) is not int or type(end) is not int or not 0 < start < end:
        raise LabError("Invalid device measurement window")
    return {"display_cadence": cadence([t for t in presented if start <= t <= end]),
            "renderer_submission": cadence([t for t in submitted if start <= t <= end]),
            "source_game_fps": {"available": False, "reason": "No independently instrumented game presents"},
            "startup_first_content_ms": execution["time_to_first_content_ms"],
            "lsfg_generation_to_display_latency": {"available": False,
                "reason": "Requires matched native generation and display identifiers; inspect native diagnostics/trace"}}
