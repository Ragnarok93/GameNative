"""A bounded, uniquely named Perfetto session; never stop somebody else's trace."""
import subprocess
import time
from pathlib import Path
from .device import LabError
from .components import sha256


class Trace:
    def __init__(self, device, telemetry):
        self.device, self.telemetry = device, telemetry
        self.name = "gamenative-" + telemetry.run_id
        self.remote = "/data/misc/perfetto-traces/" + self.name + ".pftrace"
        self.started = False
        self.pid = None

    def start(self, duration_ms):
        config = f'''unique_session_name: "{self.name}"
duration_ms: {duration_ms}
buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }} }} }}
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{ ftrace_events: "sched/sched_switch" ftrace_events: "power/cpu_frequency" ftrace_events: "power/cpu_idle" atrace_apps: "app.gamenative" atrace_categories: "gfx" atrace_categories: "view" }} }} }}
'''
        self.telemetry.directory.joinpath("perfetto-config.txt").write_text(config)
        output = self.device.call("shell", "-T", "perfetto", "--background-wait", "--txt", "-c", "-", "-o", self.remote,
                                  data=config.encode(), timeout=40)
        if not output.isdigit() or int(output) <= 1:
            raise LabError("Perfetto did not return an owned background PID")
        self.pid = int(output)
        self.started = True
        self.telemetry.event("trace_started", session=self.name)

    def finish(self):
        if not self.started:
            return
        try:
            command = self.device.call("shell", "cat", f"/proc/{self.pid}/cmdline")
            arguments = command.split("\0")
            if self.remote not in arguments or Path(arguments[0]).name != "perfetto":
                raise LabError("Perfetto PID ownership changed; refusing to signal another process")
            self.device.call("shell", "kill", "-TERM", str(self.pid))
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                try:
                    self.device.call("shell", "kill", "-0", str(self.pid))
                except LabError:
                    break
                time.sleep(.1)
            else:
                raise LabError("Owned Perfetto process did not finish")
            path = self.telemetry.directory / "trace.pftrace"
            with path.open("wb") as stream:
                result = self.device.execute([self.device.adb, "-s", self.device.serial,
                    "exec-out", "cat", self.remote], stdout=stream, stderr=subprocess.PIPE, timeout=60, check=False)
            if result.returncode or path.stat().st_size == 0:
                raise LabError("Perfetto trace collection failed: " + result.stderr.decode(errors="replace"))
            remote_hash = self.device.call("shell", "sha256sum", self.remote).split()[0]
            if remote_hash != sha256(path):
                raise LabError("Perfetto transfer hash mismatch; trace is not verified")
            self.telemetry.artifact("trace-manifest.json", {"file": path.name, "sha256": sha256(path),
                "session": self.name, "frame_timeline_availability": "Inspect trace; enabling a source does not prove frame coverage"})
        finally:
            self.started = False
            self.device.call("shell", "rm", "-f", self.remote)
