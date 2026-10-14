"""Deadline-bounded, visually supervised navigation in an existing game.

Does not launch, close, reconfigure, or attest a game. The caller must confirm
actual gameplay and supply an observe callback that rejects loading, crashes,
and blocked movement. This is exploratory evidence, never regression proof.
"""
import math
import time
from .device import LabError


def navigate(device, actions, observe, *, duration_s=300, clock=time.monotonic, stationary=False, sleeper=time.sleep):
    if (type(duration_s) not in (int, float) or not math.isfinite(duration_s)
            or not 0 < duration_s <= 300):
        raise LabError("Navigation duration must be finite and at most 300 seconds")
    if not actions and not stationary:
        raise LabError("Navigation requires a route")
    for keys, hold_ms in actions:
        if (not isinstance(keys, (list, tuple)) or len(keys) != 2
                or any(type(k) is not int or k not in {29, 32, 47, 51, 59} for k in keys)
                or len(set(keys)) != 2 or type(hold_ms) is not int
                or not 1 <= hold_ms <= 10000):
            raise LabError("Invalid bounded movement action")
    start = clock()
    deadline = start + duration_s
    count = 0
    reason = "deadline"
    while clock() < deadline:
        if not observe():
            reason = "observation_rejected"
            break
        remaining_ms = int((deadline - clock()) * 1000)
        if remaining_ms <= 0:
            break
        if stationary:
            sleeper(min(1, remaining_ms / 1000))
            continue
        keys, requested = actions[count % len(actions)]
        hold = min(requested, remaining_ms)
        device.call("shell", "input", "keycombination", "-t", str(hold),
                    *(str(k) for k in keys), timeout=hold / 1000 + 2)
        count += 1
    return {"actions": count, "elapsed_s": clock() - start, "reason": reason,
            "regression_eligible": False, "requested_duration_s": duration_s}


def execute_session(device, actions, observe, collect, close, *, duration_s=300,
                    clock=time.monotonic, stationary=False):
    """Retire a caller-owned game even if input or diagnostics fail.

    Caller is responsible for launch ownership and user permission to close.
    Collect runs before close to retain live guest diagnostics. This helper
    never restores settings or claims that all diagnostics are available.
    """
    result = {"regression_eligible": False, "navigation": None,
              "collection": None, "errors": [], "close_completed": False}
    try:
        result["navigation"] = navigate(device, actions, observe,
                                         duration_s=duration_s, clock=clock, stationary=stationary)
    except Exception as exc:
        result["errors"].append({"stage": "navigation", "error": str(exc)})
    finally:
        try:
            result["collection"] = collect()
        except Exception as exc:
            result["errors"].append({"stage": "collection", "error": str(exc)})
        finally:
            try:
                close()
                result["close_completed"] = True
            except Exception as exc:
                result["errors"].append({"stage": "close", "error": str(exc)})
    rejected = (result["navigation"] is not None and
                result["navigation"]["reason"] == "observation_rejected")
    result["status"] = "failed" if result["errors"] else "aborted" if rejected else "complete"
    return result
