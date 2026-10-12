import json
import time
import os
from datetime import datetime, timezone
from pathlib import Path


class Telemetry:
    def __init__(self, directory, run_id):
        self.directory = Path(directory) / run_id
        self.directory.mkdir(parents=True, exist_ok=False)
        self.run_id = run_id
        self.sequence = 0

    def event(self, event, **fields):
        self.sequence += 1
        record = {"schema_version": 1, "run_id": self.run_id, "sequence": self.sequence,
                  "utc": datetime.now(timezone.utc).isoformat(),
                  "host_monotonic_ns": time.monotonic_ns(), "event": event, **fields}
        with (self.directory / "events.jsonl").open("a") as output:
            output.write(json.dumps(record, sort_keys=True, allow_nan=False) + "\n")
            output.flush()
            os.fsync(output.fileno())

    def artifact(self, name, value):
        (self.directory / name).write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n")
