import json
from pathlib import Path
from .device import LabError


def compare(baseline, candidate):
    """Compare deployment/readback evidence; never infer FPS from deployment."""
    def read(path):
        root = Path(path)
        result = json.loads((root / "result.json").read_text())
        request = json.loads((root / "request.json").read_text())
        device = json.loads((root / "device.json").read_text())
        if result.get("status") != "complete" or result.get("run_id") != request.get("run_id"):
            raise LabError("Only completed, matching run evidence can be compared")
        return request, result, device
    left_request, left, left_device = read(baseline)
    right_request, right, right_device = read(candidate)
    if left_device != right_device or left_request["operation"] != right_request["operation"]:
        raise LabError("Different devices or operations are not comparable")
    changes = {key: {"baseline": left.get(key), "candidate": right.get(key)}
               for key in sorted(set(left) | set(right))
               if key != "run_id" and left.get(key) != right.get(key)}
    return {"schema_version": 1, "scope": "phase1", "changes": changes,
            "measured_performance": False, "performance_conclusion": None}
