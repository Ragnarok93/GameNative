"""A bounded, uniquely named Perfetto session; never stop somebody else's trace."""
import subprocess
from .device import LabError
from .components import sha256


class Trace:
    def __init__(self, device, telemetry):
        self.device, self.telemetry = device, telemetry
        self.name = "gamenative-" + telemetry.run_id
        self.remote = "/data/misc/perfetto-traces/" + self.name + ".pftrace"
        self.started = False

    def start(self, duration_ms):
        config = f'''unique_session_name: "{self.name}"
duration_ms: {duration_ms}
buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }} }} }}
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{ ftrace_events: "sched/sched_switch" ftrace_events: "power/cpu_frequency" ftrace_events: "power/cpu_idle" }} }} }}
'''
        self.telemetry.directory.joinpath("perfetto-config.txt").write_text(config)
        self.device.call("shell", "-T", "perfetto", "--background-wait", "--txt", "-c", "-", "-o", self.remote,
                         data=config.encode(), timeout=30)
        self.started = True
        self.telemetry.event("trace_started", session=self.name)

    def finish(self):
        if not self.started:
            return
        try:
            self.device.call("shell", "perfetto", "--attach=" + self.name, "--stop", timeout=30)
            path = self.telemetry.directory / "trace.pftrace"
            with path.open("wb") as stream:
                result = self.device.execute([self.device.adb, "-s", self.device.serial,
                    "shell", "-T", "cat", self.remote], stdout=stream, stderr=subprocess.PIPE, timeout=60, check=False)
            if result.returncode or path.stat().st_size == 0:
                raise LabError("Perfetto trace collection failed: " + result.stderr.decode(errors="replace"))
            self.telemetry.artifact("trace-manifest.json", {"file": path.name, "sha256": sha256(path),
                "session": self.name, "frame_timeline_availability": "Inspect trace; enabling a source does not prove frame coverage"})
        finally:
            self.started = False
            self.device.call("shell", "rm", "-f", self.remote)
