"""A fake zigbee2mqtt colour bulb for the MineQTT end-to-end checklist (item 31, M3-17). Python 3.9+, standard library only.

  python fake_bulb.py [--name m3bulb] [--host localhost] [--port 1883]   talk to a broker directly (MQTT 3.1.1)
  python fake_bulb.py [--name m3bulb] --lane ID                          talk to a test lane's broker (lane.sh pub/sub)
  options: --base zigbee2mqtt   --seconds N (stop after N s; default: until Ctrl+C)   --clear (delete its retained topics at the end)

It behaves as zigbee2mqtt publishes a bulb: the retained state on <base>/<name> as JSON
({"state":"ON","brightness":200,"color_mode":"xy","color":{"x":0.62,"y":0.366},"color_temp":370,"linkquality":120}),
and it obeys <base>/<name>/set like zigbee2mqtt: "state" ON, OFF or TOGGLE, "brightness" 0-254 (higher is clamped),
"color" as {"r","g","b"}, {"x","y"} or {"hue","saturation"}, "color_temp" in mireds (150-500), "transition" ignored.
Every command it took is printed, then the state it published. Its availability is "online" on <base>/<name>/availability.
A malformed command is printed and ignored, as zigbee2mqtt logs and drops it.
"""
import argparse
import json
import math
import os
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


class Bulb:
    def __init__(self):
        self.state = {"state": "OFF", "brightness": 254, "color_mode": "color_temp", "color": {"x": 0.4573, "y": 0.41}, "color_temp": 370,
                      "linkquality": 120}

    def apply(self, command):
        """Applies one /set command; returns why it was ignored, or None."""
        if not isinstance(command, dict):
            return "not a JSON object"
        state = dict(self.state)
        if "state" in command:
            value = str(command["state"]).upper()
            if value == "TOGGLE":
                state["state"] = "OFF" if state["state"] == "ON" else "ON"
            elif value in ("ON", "OFF"):
                state["state"] = value
            else:
                return "state is ON, OFF or TOGGLE"
        if "brightness" in command:
            if not isinstance(command["brightness"], (int, float)) or isinstance(command["brightness"], bool):
                return "brightness is a number"
            state["brightness"] = max(0, min(254, round(command["brightness"])))
            # zigbee2mqtt switches a bulb on with a brightness (off with 0) unless the command says otherwise.
            if "state" not in command:
                state["state"] = "ON" if state["brightness"] > 0 else "OFF"
        if "color" in command:
            xy = to_xy(command["color"])
            if xy is None:
                return "color is {r,g,b}, {x,y} or {hue,saturation}"
            state["color"] = {"x": xy[0], "y": xy[1]}
            state["color_mode"] = "xy"
            if "state" not in command:
                state["state"] = "ON"
        if "color_temp" in command:
            if not isinstance(command["color_temp"], (int, float)) or isinstance(command["color_temp"], bool):
                return "color_temp is a number of mireds"
            state["color_temp"] = max(150, min(500, round(command["color_temp"])))
            state["color_mode"] = "color_temp"
            if "state" not in command:
                state["state"] = "ON"
        self.state = state
        return None


def to_xy(color):
    """CIE xy of a zigbee2mqtt colour, or None."""
    if not isinstance(color, dict):
        return None
    if {"x", "y"} <= color.keys():
        return round(float(color["x"]), 4), round(float(color["y"]), 4)
    if color.keys() & {"r", "g", "b"}:
        rgb = [max(0.0, min(255.0, float(color.get(c, 0)))) / 255 for c in "rgb"]
    elif {"hue", "saturation"} <= color.keys():
        h, s = float(color["hue"]) % 360 / 60, max(0.0, min(100.0, float(color["saturation"]))) / 100
        x = s * (1 - abs(h % 2 - 1))
        rgb = [[s, x, 0], [x, s, 0], [0, s, x], [0, x, s], [x, 0, s], [s, 0, x]][int(h) % 6]
        rgb = [c + 1 - s for c in rgb]
    else:
        return None
    lin = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in rgb]
    X = lin[0] * 0.4124 + lin[1] * 0.3576 + lin[2] * 0.1805
    Y = lin[0] * 0.2126 + lin[1] * 0.7152 + lin[2] * 0.0722
    Z = lin[0] * 0.0193 + lin[1] * 0.1192 + lin[2] * 0.9505
    total = X + Y + Z
    if total == 0 or math.isnan(total):
        return 0.3127, 0.329
    return round(X / total, 4), round(Y / total, 4)


class SocketBroker:
    """A minimal MQTT 3.1.1 client: CONNECT, SUBSCRIBE (QoS 0), PUBLISH (QoS 0, retained), PINGREQ."""

    def __init__(self, host, port, client_id):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.lock = threading.Lock()
        body = self._string(b"MQTT") + bytes([4, 0x02]) + (30).to_bytes(2, "big") + self._string(client_id.encode())
        self._send(0x10, body)
        kind, payload = self._read()
        if kind != 0x20 or len(payload) < 2 or payload[1] != 0:
            raise SystemExit(f"fake_bulb: the broker refused the connection: {payload.hex()}")
        self.sock.settimeout(None)
        threading.Thread(target=self._ping, daemon=True).start()

    def subscribe(self, topic, on_message):
        self._send(0x82, (1).to_bytes(2, "big") + self._string(topic.encode()) + bytes([0]))
        threading.Thread(target=self._loop, args=(on_message,), daemon=True).start()

    def publish(self, topic, payload, retain):
        self._send(0x30 | (1 if retain else 0), self._string(topic.encode()) + payload.encode())

    def close(self):
        try:
            self._send(0xE0, b"")
            self.sock.close()
        except OSError:
            pass

    def _loop(self, on_message):
        while True:
            try:
                kind, payload = self._read()
            except (OSError, ConnectionError):
                return
            if kind & 0xF0 == 0x30:
                length = int.from_bytes(payload[:2], "big")
                topic = payload[2:2 + length].decode()
                rest = payload[2 + length:]
                if kind & 0x06:
                    rest = rest[2:]
                on_message(topic, rest.decode("utf-8", "replace"))

    def _ping(self):
        while True:
            time.sleep(20)
            try:
                self._send(0xC0, b"")
            except OSError:
                return

    @staticmethod
    def _string(data):
        return len(data).to_bytes(2, "big") + data

    def _send(self, header, body):
        length, encoded = len(body), bytearray()
        while True:
            byte, length = length % 128, length // 128
            encoded.append(byte | (0x80 if length else 0))
            if not length:
                break
        with self.lock:
            self.sock.sendall(bytes([header]) + bytes(encoded) + body)

    def _read(self):
        header = self._exact(1)[0]
        multiplier, length = 1, 0
        while True:
            byte = self._exact(1)[0]
            length += (byte & 0x7F) * multiplier
            multiplier *= 128
            if not byte & 0x80:
                break
        return header, self._exact(length)

    def _exact(self, n):
        data = b""
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("closed")
            data += chunk
        return data


class LaneBroker:
    """A test lane's broker through e2e/lanes/lane.sh (mosquitto_sub and mosquitto_pub in its container)."""

    def __init__(self, lane):
        self.lane = lane
        self.lane_sh = str(REPO / "e2e" / "lanes" / "lane.sh")
        self.sub = None

    def subscribe(self, topic, on_message):
        self.sub = subprocess.Popen(["sh", self.lane_sh, "sub", self.lane, "-v", "-t", topic], stdout=subprocess.PIPE, text=True, encoding="utf-8")

        def loop():
            for line in self.sub.stdout:
                line = line.rstrip("\n")
                if " " in line:
                    t, payload = line.split(" ", 1)
                    on_message(t, payload)

        threading.Thread(target=loop, daemon=True).start()

    def publish(self, topic, payload, retain):
        args = ["sh", self.lane_sh, "pub", self.lane, "-q", "1", "-t", topic]
        if retain:
            args.append("-r")
        # The payload goes through stdin (-s): on Windows, Git Bash would brace-expand a JSON argument with commas.
        args.append("-n" if payload == "" else "-s")
        subprocess.run(args, input=payload, text=True, encoding="utf-8", check=False)

    def close(self):
        if self.sub:
            self.sub.terminate()


def main():
    parser = argparse.ArgumentParser(description="A fake zigbee2mqtt colour bulb.")
    parser.add_argument("--name", default="m3bulb")
    parser.add_argument("--base", default="zigbee2mqtt")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--lane")
    parser.add_argument("--seconds", type=float)
    parser.add_argument("--clear", action="store_true")
    args = parser.parse_args()
    if "/" in args.name or "+" in args.name or "#" in args.name:
        raise SystemExit("fake_bulb: one topic level as the name, please")
    state_topic = f"{args.base}/{args.name}"
    broker = LaneBroker(args.lane) if args.lane else SocketBroker(args.host, args.port, f"fake_bulb_{args.name}_{os.getpid()}")
    bulb = Bulb()
    lock = threading.Lock()

    def publish_state():
        payload = json.dumps(bulb.state, separators=(",", ":"))
        broker.publish(state_topic, payload, True)
        print(f"state  {state_topic} {payload}", flush=True)

    def on_message(topic, payload):
        try:
            command = json.loads(payload)
        except json.JSONDecodeError:
            print(f"ignored {topic} {payload}: not JSON", flush=True)
            return
        with lock:
            problem = bulb.apply(command)
            if problem:
                print(f"ignored {topic} {payload}: {problem}", flush=True)
                return
            print(f"command {topic} {payload}", flush=True)
            publish_state()

    broker.publish(f"{state_topic}/availability", json.dumps({"state": "online"}), True)
    publish_state()
    broker.subscribe(f"{state_topic}/set", on_message)
    print(f"fake bulb {args.name} listens on {state_topic}/set", flush=True)
    try:
        if args.seconds:
            time.sleep(args.seconds)
        else:
            while True:
                time.sleep(3600)
    except KeyboardInterrupt:
        pass
    finally:
        if args.clear:
            broker.publish(state_topic, "", True)
            broker.publish(f"{state_topic}/availability", "", True)
        broker.close()


if __name__ == "__main__":
    sys.exit(main())
