import uuid
from .device import LabError
from .telemetry import Telemetry


def execute(device, request, results, profile=None):
    request = dict(request, run_id=uuid.uuid4().hex, protocol_version=1)
    telemetry = Telemetry(results, request["run_id"])
    telemetry.artifact("request.json", request)
    if profile is not None:
        telemetry.artifact("profile.json", profile)
    telemetry.event("validating", operation=request["operation"])
    try:
        info = device.info()
        telemetry.artifact("device.json", info)
        if profile:
            for key in ("model", "soc"):
                if info[key] != profile["device"][key]:
                    raise LabError(f"Device {key} does not match profile")
        telemetry.event("executing")
        result = device.bridge(request)
        telemetry.artifact("result.json", result)
        telemetry.event("complete", measured_performance=False)
        return {"run_id": request["run_id"], "results": str(telemetry.directory), "result": result}
    except BaseException as exc:
        telemetry.event("failed", error=str(exc), recovery="If interrupted during configuration, run recover before another mutation")
        raise
