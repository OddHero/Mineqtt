"""Drives a test Home Assistant for the MineQTT end-to-end checklist. Python 3.9+, standard library only.

  python ha.py [--url URL] onboard                  create the owner user and store a long-lived token
  python ha.py [--url URL] login                    log in as that owner on an onboarded instance and store a token
  python ha.py [--url URL] mqtt [BROKER] [PORT]     add the MQTT integration (default: mosquitto 1883)
  python ha.py [--url URL] devices                  MineQTT and other MQTT devices with their via_device
  python ha.py [--url URL] entities [TEXT]          MQTT entities: entity_id, unique_id, naming, state
  python ha.py [--url URL] state ENTITY_ID          one entity's state and attributes as JSON
  python ha.py [--url URL] call DOMAIN.SERVICE JSON call a service, e.g. light.turn_on '{"entity_id":"light.x"}'
  python ha.py [--url URL] restart                  restart Home Assistant and wait until it answers again
  python ha.py [--url URL] dump FILE                write both registries and all states to FILE
  python ha.py [--url URL] triggers DEVICE          the device automation triggers of the device with that name
  python ha.py [--url URL] watch DEVICE [SECONDS]   print each MQTT device trigger of the device that fires (default 30 s)
  python ha.py [--url URL] label create NAME        create a label; its id is the slug of NAME ("MineQTT read": mineqtt_read)
  python ha.py [--url URL] label add LABEL TARGET...     add a label (id or name) to entities (domain.object_id)
                                                    or devices (by name)
  python ha.py [--url URL] label remove LABEL TARGET...  take it off again
  python ha.py [--url URL] label list               every label with its id and the entities and devices carrying it
  python ha.py [--url URL] automation from-blueprint FILE ID INPUTS_JSON
                                                    create or replace automation ID from a blueprint below
                                                    blueprints/automation/ (a FILE without a folder is mineqtt/FILE)
  python ha.py [--url URL] automation delete ID     delete an automation the automation editor wrote
  python ha.py [--url URL] logbook ENTITY_ID [MINUTES]   the entity's logbook entries of the last MINUTES (default 10)
  python ha.py [--url URL] trace AUTOMATION [RUN_ID|last]
                                                    an automation's stored runs, or one run's full trace as JSON
                                                    (AUTOMATION: its config id or its automation.* entity id)

URL defaults to $HA_URL or http://localhost:8123. The token is kept in e2e/.state/ (ignored by git). The test owner
is "mineqtt" with the password "mineqtt-e2e": this is a throwaway instance, never use this against a real one.
"""
import base64
import datetime
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
        sock, send, receive = self.ws_connect()
        results = [self.ws_result(send, receive, number, command) for number, command in enumerate(commands, start=1)]
        sock.close()
        return results

    def ws_events(self, commands, seconds):
        """Sends each subscribing command dict, then returns every event they deliver within the given seconds."""
        sock, send, receive = self.ws_connect()
        for number, command in enumerate(commands, start=1):
            self.ws_result(send, receive, number, command)
        events = []
        deadline = time.time() + seconds
        while (left := deadline - time.time()) > 0:
            sock.settimeout(left)
            try:
                reply = receive()
            except (socket.timeout, TimeoutError):
                break
            if reply.get("type") == "event":
                events.append(reply)
        sock.close()
        return events

    @staticmethod
    def ws_result(send, receive, number, command):
        send(dict(command, id=number))
        while True:
            reply = receive()
            if reply.get("id") == number and reply.get("type") == "result":
                break
        if not reply.get("success"):
            sys.exit(f"{command['type']} failed: {reply.get('error')}")
        return reply.get("result")

    def ws_connect(self):
        """An authenticated /api/websocket connection: the socket and its send and receive functions."""
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
        return sock, send, receive


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


def login(ha, _args):
    """A token for an instance that is already onboarded (a fresh checkout has no e2e/.state/): the login flow with
    the test owner, then a long-lived token. Long-lived token names must be unique per user, hence the timestamp."""
    ha.wait_until_up()
    client_id = ha.url + "/"
    flow = ha.request("POST", "/auth/login_flow", {
        "client_id": client_id, "handler": ["homeassistant", None], "redirect_uri": client_id + "?auth_callback=1",
    }, auth=False)
    result = ha.request("POST", f"/auth/login_flow/{flow['flow_id']}", {
        "client_id": client_id, "username": USER, "password": PASSWORD,
    }, auth=False)
    if result.get("type") != "create_entry":
        sys.exit(f"Login refused: {result.get('errors') or result}")
    tokens = ha.request("POST", "/auth/token", {
        "grant_type": "authorization_code", "code": result["result"], "client_id": client_id,
    }, form=True, auth=False)
    ha.token = tokens["access_token"]
    name = "mineqtt-e2e " + datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    long_lived = ha.ws_commands({"type": "auth/long_lived_access_token", "client_name": name, "lifespan": 365})[0]
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(ha.token_file, "w", encoding="utf-8") as f:
        f.write(long_lived)
    print(f"Logged in to {ha.url}; token stored in {ha.token_file}")


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


def device_named(ha, name):
    for d in ha.ws_commands({"type": "config/device_registry/list"})[0]:
        if (d.get("name_by_user") or d.get("name")) == name:
            return d
    sys.exit(f"No device named {name!r}")


def triggers(ha, args):
    """The device automation triggers Home Assistant offers for a device (its MQTT device triggers among them)."""
    device = device_named(ha, args[0])
    for trigger in ha.ws_commands({"type": "device_automation/trigger/list", "device_id": device["id"]})[0]:
        print(json.dumps(trigger, sort_keys=True))


def watch(ha, args):
    """Subscribes to every MQTT device trigger of a device for SECONDS and prints each that fires, with its payload."""
    device = device_named(ha, args[0])
    seconds = float(args[1]) if len(args) > 1 else 30
    offered = [t for t in ha.ws_commands({"type": "device_automation/trigger/list", "device_id": device["id"]})[0]
               if t.get("domain") == "mqtt"]
    commands = [{"type": "subscribe_trigger", "trigger": {k: v for k, v in t.items() if k != "metadata"}} for t in offered]
    print(f"watching {len(commands)} triggers of {args[0]!r} for {seconds:g} s", flush=True)
    for event in ha.ws_events(commands, seconds):
        # The event carries the id of the subscription it answers, the trigger's position in the list.
        fired = offered[event["id"] - 1]
        payload = event["event"]["variables"]["trigger"].get("payload")
        print(f"fired {fired['type']} {fired['subtype']} payload={payload!r}", flush=True)


def dump(ha, args):
    device_list, entity_list = registries(ha)
    with open(args[0], "w", encoding="utf-8") as f:
        json.dump({"devices": device_list, "entities": entity_list, "states": ha.request("GET", "/api/states")}, f, indent=1)
    print(f"wrote {args[0]}")


def find_label(ha, text):
    """The label whose id or name is TEXT."""
    for entry in ha.ws_commands({"type": "config/label_registry/list"})[0]:
        if text in (entry["label_id"], entry["name"]):
            return entry
    sys.exit(f"No label with the id or name {text!r} (see 'python ha.py label list')")


def label(ha, args):
    """Labels as the twin's read and control gates use them (M3 plan D3, D12): create, add, remove, list."""
    action = args[0] if args else ""
    if action == "create" and len(args) == 2:
        created = ha.ws_commands({"type": "config/label_registry/create", "name": args[1]})[0]
        print(f"created label {created['name']!r} with the id {created['label_id']}")
    elif action in ("add", "remove") and len(args) >= 3:
        label_id = find_label(ha, args[1])["label_id"]
        for target in args[2:]:
            if "." in target:
                entry = ha.ws_commands({"type": "config/entity_registry/get", "entity_id": target})[0]
                command = {"type": "config/entity_registry/update", "entity_id": target}
            else:
                entry = device_named(ha, target)
                command = {"type": "config/device_registry/update", "device_id": entry["id"]}
            labels = set(entry.get("labels") or [])
            labels = labels | {label_id} if action == "add" else labels - {label_id}
            ha.ws_commands(dict(command, labels=sorted(labels)))
            print(f"{target}: labels {sorted(labels)}")
    elif action == "list" and len(args) == 1:
        labels, device_list, entity_list = ha.ws_commands({"type": "config/label_registry/list"},
                                                          {"type": "config/device_registry/list"},
                                                          {"type": "config/entity_registry/list"})
        for entry in sorted(labels, key=lambda e: e["label_id"]):
            on_devices = [d.get("name_by_user") or d.get("name") for d in device_list
                          if entry["label_id"] in (d.get("labels") or [])]
            on_entities = [e["entity_id"] for e in entity_list if entry["label_id"] in (e.get("labels") or [])]
            print(f"{entry['label_id']:24} name={entry['name']!r} entities={sorted(on_entities)} "
                  f"devices={sorted(on_devices)}")
    else:
        sys.exit("usage: label create NAME | label add|remove LABEL TARGET... | label list")


def automation_entity(ha, config_id):
    """The automation.* entity of the automation with that config id, or None while Home Assistant has not loaded it.
    A loaded automation names its config id in its attributes; one that failed to set up only has the entity id its
    alias (the config id, see automation()) gives it."""
    slug = "automation." + "".join(c if c.isalnum() else "_" for c in config_id.lower())
    for item in ha.request("GET", "/api/states"):
        if item["entity_id"].startswith("automation.") and (item["attributes"].get("id") == config_id
                                                          or item["entity_id"] == slug):
            return item
    return None


def automation(ha, args):
    """Automations through the automation editor's REST API, which writes /config/automations.yaml and reloads them."""
    action = args[0] if args else ""
    if action == "from-blueprint" and len(args) == 4:
        path, config_id = args[1].replace("\\", "/"), args[2]
        if "/" not in path:
            path = "mineqtt/" + path
        inputs = json.loads(args[3])
        if not isinstance(inputs, dict):
            sys.exit("INPUTS_JSON must be a JSON object of blueprint inputs, for example '{}'")
        # Home Assistant validates the blueprint and the inputs here and answers HTTP 400 with the reason.
        ha.request("POST", f"/api/config/automation/config/{urllib.parse.quote(config_id, safe='')}", {
            "id": config_id, "alias": config_id, "description": f"MineQTT e2e, from the blueprint {path}",
            "use_blueprint": {"path": path, "input": inputs},
        })
        deadline = time.time() + 30
        while (entity := automation_entity(ha, config_id)) is None and time.time() < deadline:
            time.sleep(1)
        if entity is None:
            sys.exit(f"Home Assistant accepted automation {config_id!r} but did not load it within 30 s")
        print(f"automation {config_id!r} from {path}: {entity['entity_id']} state={entity['state']!r}")
        if entity["state"] == "unavailable":
            sys.exit("The automation did not set up: its reason is in this Home Assistant's log (docker compose logs)")
    elif action == "delete" and len(args) == 2:
        ha.request("DELETE", f"/api/config/automation/config/{urllib.parse.quote(args[1], safe='')}")
        print(f"deleted automation {args[1]!r}")
    else:
        sys.exit("usage: automation from-blueprint FILE ID INPUTS_JSON | automation delete ID")


def logbook(ha, args):
    """The entity's logbook entries (the control blueprint writes one per accepted Minecraft request, M3 plan D12)."""
    if not args:
        sys.exit("usage: logbook ENTITY_ID [MINUTES]")
    minutes = float(args[1]) if len(args) > 1 else 10
    end = datetime.datetime.now(datetime.timezone.utc)
    start = end - datetime.timedelta(minutes=minutes)
    query = urllib.parse.urlencode({"entity": args[0], "end_time": end.isoformat()})
    entries = ha.request("GET", f"/api/logbook/{urllib.parse.quote(start.isoformat())}?{query}")
    for entry in entries:
        text = entry.get("message") or (f"state {entry['state']!r}" if "state" in entry else "")
        print(f"{entry.get('when')} {entry.get('name')!r}: {text}")
    if not entries:
        print(f"no logbook entries of {args[0]} in the last {minutes:g} minutes")


def trace(ha, args):
    """An automation's stored runs, or one run's full trace: its variables and each step's rendered service data."""
    if not args:
        sys.exit("usage: trace AUTOMATION [RUN_ID|last]")
    config_id = args[0]
    if config_id.startswith("automation."):
        config_id = ha.request("GET", "/api/states/" + config_id)["attributes"].get("id")
        if not config_id:
            sys.exit(f"{args[0]} has no config id (only automations with an id keep traces)")
    runs = ha.ws_commands({"type": "trace/list", "domain": "automation", "item_id": config_id})[0]
    runs.sort(key=lambda run: run["timestamp"]["start"])
    if len(args) == 1:
        for run in runs:
            print(f"{run['run_id']} {run['timestamp']['start']} state={run['state']} "
                  f"execution={run.get('script_execution')} last_step={run.get('last_step')}"
                  + (" not_triggered" if run.get("not_triggered") else "")
                  + (f" error={run['error']!r}" if run.get("error") else ""))
        if not runs:
            print(f"no stored runs of automation {config_id!r}")
        return
    run_id = args[1]
    if run_id == "last":
        # Newer versions also keep traces of trigger evaluations that did not fire; "last" means the last real run.
        fired = [run for run in runs if not run.get("not_triggered")]
        if not fired:
            sys.exit(f"no stored runs of automation {config_id!r}")
        run_id = fired[-1]["run_id"]
    print(json.dumps(ha.ws_commands({"type": "trace/get", "domain": "automation", "item_id": config_id,
                                     "run_id": run_id})[0], indent=2))


COMMANDS = {"onboard": onboard, "login": login, "mqtt": mqtt, "devices": devices, "entities": entities, "state": state,
            "call": call, "restart": restart, "dump": dump, "triggers": triggers, "watch": watch,
            "label": label, "automation": automation, "logbook": logbook, "trace": trace}


def main(argv):
    url = os.environ.get("HA_URL", "http://localhost:8123")
    if len(argv) >= 2 and argv[0] == "--url":
        url, argv = argv[1], argv[2:]
    if not argv or argv[0] not in COMMANDS:
        sys.exit(__doc__)
    COMMANDS[argv[0]](HomeAssistant(url), argv[1:])


if __name__ == "__main__":
    main(sys.argv[1:])
