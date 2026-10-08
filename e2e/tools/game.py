"""Game-side E2E helper for a Fabric dev server (./gradlew :fabric:runServer) of this repository.
  game.py cfg key=value ...                 edit fabric/run/config/mineqtt.json (dotted keys, JSON or string values)
  game.py pub [-r] [-b broker] <topic> <payload>   publish QoS 1 via the e2e Mosquitto (broker: m2 | m15)
  game.py sub <seconds> <filter...>          print retained + live messages (retain flag, topic, payload)
  game.py rcon <command...>                  RCON to the dev server
  game.py pid                                PID of this checkout's dev server JVM (Windows, PowerShell)
  game.py v1lamp x y z topic r g b bright lit   turn a placed lamp into v1 data (keys as v1 wrote them)
  game.py v1 x y z emitter|publisher topic   turn a placed emitter/publisher into v1 data
  game.py v1motion x y z topic count         turn a placed motion sensor into v1 data
  game.py v1disc topic area                  publish v1's retained light discovery for a topic
  game.py retained <filter...>               list retained topics
  game.py clear <filter...>                  clear retained topics under the filters

Environment: MINEQTT_RCON_PORT (default 25611), MINEQTT_RCON_PASSWORD (default mineqtt-e2e),
MINEQTT_E2E_MOSQUITTO / MINEQTT_E2E_MOSQUITTO15 (compose container names of the two brokers).
"""
import json
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CONFIG = REPO / "fabric" / "run" / "config" / "mineqtt.json"
CONTAINERS = {"m2": os.environ.get("MINEQTT_E2E_MOSQUITTO", "mineqtt-e2e-mosquitto-1"),
              "m15": os.environ.get("MINEQTT_E2E_MOSQUITTO15", "mineqtt-e2e-mosquitto-1.5-1")}
RCON = Path(__file__).resolve().parent / "rcon.py"
RCON_PORT = os.environ.get("MINEQTT_RCON_PORT", "25611")
RCON_PASSWORD = os.environ.get("MINEQTT_RCON_PASSWORD", "mineqtt-e2e")


def cfg(args):
    with open(CONFIG, encoding="utf-8") as f:
        root = json.load(f)
    for assignment in args:
        key, value = assignment.split("=", 1)
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            parsed = value
        node = root
        parts = key.split(".")
        for part in parts[:-1]:
            node = node[part]
        node[parts[-1]] = parsed
    with open(CONFIG, "w", encoding="utf-8") as f:
        json.dump(root, f, indent=2)
        f.write("\n")
    print("config:", ", ".join(args))


def broker_args(args):
    container = CONTAINERS["m2"]
    if args and args[0] == "-b":
        container, args = CONTAINERS[args[1]], args[2:]
    return container, args


def pub(args):
    retain = args and args[0] == "-r"
    if retain:
        args = args[1:]
    container, args = broker_args(args)
    topic, payload = args[0], args[1]
    cmd = ["docker", "exec", container, "mosquitto_pub", "-q", "1", "-t", topic]
    cmd += ["-r"] if retain else []
    cmd += ["-n"] if payload == "" else ["-m", payload]
    subprocess.run(cmd, check=True)


def sub(args):
    container, args = broker_args(args)
    seconds, filters = args[0], args[1:]
    cmd = ["docker", "exec", container, "mosquitto_sub", "-v", "-W", seconds, "-F", "%r %t %p"]
    for f in filters:
        cmd += ["-t", f]
    subprocess.run(cmd)


def rcon(args):
    subprocess.run([sys.executable, str(RCON), RCON_PORT, RCON_PASSWORD, " ".join(args)], check=True)


def pid(_):
    script = ("Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | "
              "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }")
    out = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True).stdout
    for line in out.splitlines():
        process_id, _, command = line.partition("\t")
        if "TransformerRuntime" in command and str(REPO) in command:
            print(process_id)


def item(slot, name):
    return '{Slot:%db,id:"minecraft:paper",count:1,components:{"minecraft:custom_name":"%s"}}' % (slot, name)


def data(x, y, z, nbt):
    rcon([f"data remove block {x} {y} {z} mineqtt"])
    rcon([f"data merge block {x} {y} {z} {nbt}"])


def v1lamp(args):
    x, y, z, topic, r, g, b, bright, lit = args[:9]
    nbt = ('{topic:"%s",lastTabId:"settings",ownerName:"Steve",privateMode:0b,Items:[%s],'
           'Red:%s,Green:%s,Blue:%s,TargetRed:%s,TargetGreen:%s,TargetBlue:%s,Brightness:%s,Lit:%s}') % (
        topic, item(0, topic), r, g, b, r, g, b, bright, "1b" if lit == "1" else "0b")
    data(x, y, z, nbt)


def v1(args):
    x, y, z, kind, topic = args[:5]
    nbt = '{topic:"%s",lastTabId:"settings",ownerName:"Steve",privateMode:0b,Items:[%s]' % (topic, item(0, topic))
    if kind == "emitter":
        nbt += ',currentSubscribedTopic:"%s"' % topic
    data(x, y, z, nbt + "}")


def v1motion(args):
    x, y, z, topic, count = args[:5]
    nbt = ('{topic:"%s",lastTabId:"settings",ownerName:"Steve",privateMode:0b,Items:[%s],'
           'MotionDetected:%db,LastMotionTime:1759917600000L,LastCount:%s}') % (
        topic, item(0, topic), 1 if int(count) > 0 else 0, count)
    data(x, y, z, nbt)


def v1disc(args):
    """Byte-for-byte what v1's HomeAssistantLight.toJson() published (Gson field order), retained QoS 1."""
    topic, area = args[0], args[1]
    unique = "".join(c if c.isalnum() and c.isascii() or c in "_-" else "_" for c in topic)
    config = {
        "name": "RGB LED " + topic, "unique_id": unique, "schema": "json", "command_topic": topic, "retain": False,
        "state_topic": topic + "/state", "brightness": True, "brightness_scale": 255,
        "supported_color_modes": ["rgb", "hs", "xy"], "color_temp": True, "min_mireds": 153, "max_mireds": 500,
        "device": {"identifiers": unique, "name": "MineQTT RGB LED", "model": "RGB LED Block",
                   "manufacturer": "MineQTT", "sw_version": "1.0", "suggested_area": area},
        "origin": {"name": "MineQTT", "sw": "1.0", "support_url": "https://github.com/yourusername/mineqtt"},
    }
    pub(["-r", f"homeassistant/light/{unique}/config", json.dumps(config, separators=(",", ":"))])
    print(f"homeassistant/light/{unique}/config")


def retained_topics(container, filters):
    cmd = ["docker", "exec", container, "mosquitto_sub", "-W", "2", "-F", "%r\t%t\t%p"]
    for f in filters:
        cmd += ["-t", f]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8").stdout
    return [line.split("\t", 2) for line in out.splitlines() if line.startswith("1\t")]


def retained(args):
    container, args = broker_args(args)
    for _, topic, payload in retained_topics(container, args):
        print(topic, payload[:160])


def clear(args):
    container, args = broker_args(args)
    topics = sorted({t for _, t, _ in retained_topics(container, args)})
    for topic in topics:
        subprocess.run(["docker", "exec", container, "mosquitto_pub", "-q", "1", "-r", "-n", "-t", topic], check=True)
    print(f"cleared {len(topics)} retained topics")


if __name__ == "__main__":
    {"cfg": cfg, "pub": pub, "sub": sub, "rcon": rcon, "pid": pid, "v1lamp": v1lamp, "v1": v1, "v1motion": v1motion,
     "v1disc": v1disc, "retained": retained, "clear": clear}[sys.argv[1]](sys.argv[2:])
