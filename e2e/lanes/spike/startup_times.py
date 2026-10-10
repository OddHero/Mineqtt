"""Spike measurement: seconds from container start to the server's "Done (" line and to MineQTT's MQTT connect.

  python startup_times.py <container>...

Reads `docker inspect` (State.StartedAt) and `docker logs --timestamps` of the current run of each container.
"""
import subprocess
import sys
from datetime import datetime


def ts(value):
    # Docker prints nanoseconds; datetime takes microseconds.
    value = value.strip().rstrip("Z")
    if "." in value:
        head, frac = value.split(".", 1)
        value = head + "." + frac[:6]
    return datetime.fromisoformat(value)


def main():
    for name in sys.argv[1:]:
        started = ts(subprocess.run(["docker", "inspect", "-f", "{{.State.StartedAt}}", name],
                                    capture_output=True, text=True, check=True).stdout)
        logs = subprocess.run(["docker", "logs", "--timestamps", "--since", started.isoformat() + "Z", name],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        done = connected = None
        server_done = ""
        for line in (logs.stdout + logs.stderr).splitlines():
            stamp, _, text = line.partition(" ")
            if done is None and "Done (" in text:
                done = ts(stamp)
                server_done = text[text.index("Done ("):].split(")")[0] + ")"
            if connected is None and "Connected to MQTT broker" in text:
                connected = ts(stamp)
        fmt = lambda t: f"{(t - started).total_seconds():6.2f}s" if t else "   n/a"
        print(f"{name}: Done at {fmt(done)} [{server_done}], MQTT connected at {fmt(connected)} after container start")


if __name__ == "__main__":
    main()
