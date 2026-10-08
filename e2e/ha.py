"""Drives a test Home Assistant for the MineQTT end-to-end checklist. Python 3.9+, standard library only.

  python ha.py [--url URL] onboard                  create the owner user and store a long-lived token
  python ha.py [--url URL] mqtt [BROKER] [PORT]     add the MQTT integration (default: mosquitto 1883)
  python ha.py [--url URL] devices                  MineQTT and other MQTT devices with their via_device
  python ha.py [--url URL] entities [TEXT]          MQTT entities: entity_id, unique_id, naming, state
  python ha.py [--url URL] state ENTITY_ID          one entity's state and attributes as JSON
  python ha.py [--url URL] call DOMAIN.SERVICE JSON call a service, e.g. light.turn_on '{"entity_id":"light.x"}'
  python ha.py [--url URL] restart                  restart Home Assistant and wait until it answers again
  python ha.py [--url URL] dump FILE                write both registries and all states to FILE

URL defaults to $HA_URL or http://localhost:8123. The token is kept in e2e/.state/ (ignored by git). The test owner
is "mineqtt" with the password "mineqtt-e2e": this is a throwaway instance, never use this against a real one.
"""
import base64
import json
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

STATE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".state")
USER, PASSWORD = "mineqtt", "mineqtt-e2e"


class HomeAssistant:
    def __init__(self, url):
        self.url = url.rstrip("/")
        parsed = urllib.parse.urlparse(self.url)
        self.host, self.port = parsed.hostname, parsed.port or 80
        self.token_file = os.path.join(STATE_DIR, f"{self.host}_{self.port}.token")
        self.token = None
        if os.path.exists(self.token_file):
            with open(self.token_file, encoding="utf-8") as f:
                self.token = f.read().strip()

    # --- REST ---------------------------------------------------------------------------------------------------

    def request(self, method, path, body=None, form=False, auth=True):
        data, headers = None, {}
        if body is not None:
            if form:
                data = urllib.parse.urlencode(body).encode()
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            else:
                data = json.dumps(body).encode()
                headers["Content-Type"] = "application/json"
        if auth:
            if not self.token:
                sys.exit(f"No token for {self.url}: run 'python ha.py --url {self.url} onboard' first")
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(self.url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                text = resp.read().decode()
        except urllib.error.HTTPError as e:
            sys.exit(f"{method} {path} -> HTTP {e.code}: {e.read().decode(errors='replace')}")
        return json.loads(text) if text else None

    def wait_until_up(self, timeout=300):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(self.url + "/api/onboarding", timeout=5):
                    return
            except urllib.error.HTTPError:
                return  # answering (an onboarded instance may refuse this endpoint)
            except OSError:
                time.sleep(2)
        sys.exit(f"{self.url} did not come up within {timeout} s")

    # --- WebSocket (RFC 6455 client, text frames only) ------------------------------------------------------------

    def ws_commands(self, *commands):
        """Authenticates on /api/websocket, sends each command dict and returns their results in order."""
        sock = socket.create_connection((self.host, self.port), timeout=30)
        key = base64.b64encode(os.urandom(16)).decode()
        sock.sendall((f"GET /api/websocket HTTP/1.1\r\nHost: {self.host}:{self.port}\r\nUpgrade: websocket\r\n"
                      f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        reader = sock.makefile("rb")
        status = reader.readline()
        if b" 101 " not in status:
            sys.exit(f"WebSocket upgrade refused: {status!r}")
        while reader.readline() not in (b"\r\n", b""):
            pass

        def send(obj):
            payload = json.dumps(obj).encode()
            mask = os.urandom(4)
            header = bytes([0x81])
            if len(payload) < 126:
                header += bytes([0x80 | len(payload)])
            elif len(payload) < 65536:
                header += bytes([0x80 | 126]) + struct.pack(">H", len(payload))
            else:
                header += bytes([0x80 | 127]) + struct.pack(">Q", len(payload))
            sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

        def receive():
            message = b""
            while True:
                first, second = reader.read(2)
                length = second & 0x7F
                if length == 126:
                    length = struct.unpack(">H", reader.read(2))[0]
                elif length == 127:
                    length = struct.unpack(">Q", reader.read(8))[0]
                chunk = reader.read(length)
                opcode = first & 0x0F
                if opcode == 0x8:
                    sys.exit("WebSocket closed by Home Assistant")
                if opcode == 0x9:  # ping
                    continue
                message += chunk
                if first & 0x80:
                    return json.loads(message)

        if receive().get("type") != "auth_required":
            sys.exit("Unexpected WebSocket greeting")
        send({"type": "auth", "access_token": self.token})
        if receive().get("type") != "auth_ok":
            sys.exit("WebSocket authentication failed")
        results = []
        for number, command in enumerate(commands, start=1):
            send(dict(command, id=number))
            while True:
                reply = receive()
                if reply.get("id") == number and reply.get("type") == "result":
                    break
            if not reply.get("success"):
                sys.exit(f"{command['type']} failed: {reply.get('error')}")
            results.append(reply.get("result"))
        sock.close()
        return results


def onboard(ha, _args):
    ha.wait_until_up()
    client_id = ha.url + "/"
    code = ha.request("POST", "/api/onboarding/users", {
        "client_id": client_id, "name": "MineQTT E2E", "username": USER, "password": PASSWORD, "language": "en",
    }, auth=False)["auth_code"]
    tokens = ha.request("POST", "/auth/token", {
        "grant_type": "authorization_code", "code": code, "client_id": client_id,
    }, form=True, auth=False)
    ha.token = tokens["access_token"]
    ha.request("POST", "/api/onboarding/core_config", {})
    ha.request("POST", "/api/onboarding/analytics", {})
    ha.request("POST", "/api/onboarding/integration", {"client_id": client_id, "redirect_uri": client_id + "?auth_callback=1"})
    long_lived = ha.ws_commands({"type": "auth/long_lived_access_token", "client_name": "mineqtt-e2e", "lifespan": 365})[0]
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(ha.token_file, "w", encoding="utf-8") as f:
        f.write(long_lived)
    print(f"Onboarded {ha.url}; token stored in {ha.token_file}")


def form_defaults(schema, values):
    """Answers a serialized flow form: the given values, else each required field's default (sections recurse).
    Required fields without a default get the neutral choice (false, or a select's first option, i.e. "off")."""
    body = {}
    for field in schema:
        name = field["name"]
        if name in values:
            body[name] = values[name]
        elif field.get("type") == "expandable":
            body[name] = form_defaults(field["schema"], values)
        elif "default" in field:
            body[name] = field["default"]
        elif field.get("required"):
            selector = field.get("selector", {})
            if "boolean" in selector:
                body[name] = False
            elif "select" in selector:
                option = selector["select"]["options"][0]
                body[name] = option["value"] if isinstance(option, dict) else option
            else:
                sys.exit(f"No value for the required field {name!r}")
    return body


def mqtt(ha, args):
    broker = args[0] if args else "mosquitto"
    port = int(args[1]) if len(args) > 1 else 1883
    step = ha.request("POST", "/api/config/config_entries/flow", {"handler": "mqtt", "show_advanced_options": False})
    while True:
        kind = step["type"]
        if kind == "create_entry":
            print(f"MQTT integration added: {step['title']}")
            return
        if kind == "abort":
            print(f"MQTT flow aborted: {step['reason']}")
            return
        if kind == "menu":
            body = {"next_step_id": "broker"}
        elif kind == "form" and step["step_id"] == "broker":
            if step.get("errors"):
                ha.request("DELETE", "/api/config/config_entries/flow/" + step["flow_id"])
                sys.exit(f"Broker step refused: {step['errors']}")
            body = form_defaults(step["data_schema"], {"broker": broker, "port": port})
        else:
            sys.exit(f"Unexpected flow step: {json.dumps(step)}")
        step = ha.request("POST", "/api/config/config_entries/flow/" + step["flow_id"], body)


def registries(ha):
    devices, entities = ha.ws_commands({"type": "config/device_registry/list"}, {"type": "config/entity_registry/list"})
    return devices, entities


def devices(ha, _args):
    device_list, entity_list = registries(ha)
    by_id = {d["id"]: d for d in device_list}
    mqtt_ids = {e["device_id"] for e in entity_list if e["platform"] == "mqtt" and e.get("device_id")}
    for d in sorted(device_list, key=lambda d: (d.get("via_device_id") is not None, d["name"] or "")):
        if d["id"] not in mqtt_ids and d.get("manufacturer") != "MineQTT":
            continue
        via = by_id.get(d.get("via_device_id"), {}).get("name") if d.get("via_device_id") else None
        identifiers = ",".join(i[1] for i in d["identifiers"])
        print(f"{d['name']!r:28} model={d.get('model')!r} model_id={d.get('model_id')!r} via={via!r} ids={identifiers}")


def entities(ha, args):
    text = args[0] if args else ""
    device_list, entity_list = registries(ha)
    names = {d["id"]: d["name"] for d in device_list}
    states = {s["entity_id"]: s for s in ha.request("GET", "/api/states")}
    for e in sorted(entity_list, key=lambda e: e["entity_id"]):
        if e["platform"] != "mqtt" or (text and text not in e["entity_id"] and text not in e["unique_id"]):
            continue
        state = states.get(e["entity_id"], {})
        print(f"{e['entity_id']:52} state={state.get('state')!r:13} unique_id={e['unique_id']} "
              f"device={names.get(e.get('device_id'))!r} has_entity_name={e.get('has_entity_name')} "
              f"original_name={e.get('original_name')!r} friendly_name={state.get('attributes', {}).get('friendly_name')!r}"
              + (f" disabled_by={e['disabled_by']}" if e.get("disabled_by") else ""))


def state(ha, args):
    print(json.dumps(ha.request("GET", "/api/states/" + args[0]), indent=2))


def call(ha, args):
    domain, service = args[0].split(".", 1)
    ha.request("POST", f"/api/services/{domain}/{service}", json.loads(args[1]) if len(args) > 1 else {})
    print(f"called {args[0]}")


def restart(ha, _args):
    ha.request("POST", "/api/services/homeassistant/restart", {})
    time.sleep(10)
    ha.wait_until_up()
    deadline = time.time() + 300
    while time.time() < deadline:
        try:
            if ha.request("GET", "/api/config").get("state") == "RUNNING":
                print(f"{ha.url} restarted")
                return
        except (SystemExit, OSError):
            pass  # still starting
        time.sleep(2)
    sys.exit("Home Assistant did not reach RUNNING after the restart")


def dump(ha, args):
    device_list, entity_list = registries(ha)
    with open(args[0], "w", encoding="utf-8") as f:
        json.dump({"devices": device_list, "entities": entity_list, "states": ha.request("GET", "/api/states")}, f, indent=1)
    print(f"wrote {args[0]}")


COMMANDS = {"onboard": onboard, "mqtt": mqtt, "devices": devices, "entities": entities, "state": state,
            "call": call, "restart": restart, "dump": dump}


def main(argv):
    url = os.environ.get("HA_URL", "http://localhost:8123")
    if len(argv) >= 2 and argv[0] == "--url":
        url, argv = argv[1], argv[2:]
    if not argv or argv[0] not in COMMANDS:
        sys.exit(__doc__)
    COMMANDS[argv[0]](HomeAssistant(url), argv[1:])


if __name__ == "__main__":
    main(sys.argv[1:])
