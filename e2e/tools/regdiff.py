"""regdiff.py <dump.json> [unique_id...]: entity registry entries (id, entity_id, device) for the given unique_ids.
regdiff.py <dump.json> --devices TEXT: MQTT devices with an identifier containing TEXT (id, area, identifiers, entities),
to see whether Home Assistant reused, merged or left a device empty."""
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
devices = {x["id"]: x for x in d["devices"]}
states = {s["entity_id"]: s["state"] for s in d["states"]}
if len(sys.argv) == 4 and sys.argv[2] == "--devices":
    for dev in devices.values():
        ids = [i[1] for i in dev.get("identifiers", []) if i[0] == "mqtt"]
        if any(sys.argv[3] in i for i in ids):
            owned = [e["unique_id"] for e in d["entities"] if e.get("device_id") == dev["id"]]
            print(f"device_id={dev['id']} name={dev.get('name')!r} area={dev.get('area_id')!r} ids={sorted(ids)} "
                  f"entities={len(owned)} {owned}")
    sys.exit()
for e in d["entities"]:
    if e["platform"] == "mqtt" and (len(sys.argv) < 3 or e["unique_id"] in sys.argv[2:]):
        dev = devices.get(e.get("device_id"), {})
        via = devices.get(dev.get("via_device_id"), {}).get("name")
        print(f"{e['unique_id']:14} reg_id={e['id']} entity_id={e['entity_id']} state={states.get(e['entity_id'])} "
              f"device={dev.get('name')!r} device_id={e.get('device_id')} area={dev.get('area_id')!r} "
              f"ids={[i[1] for i in dev.get('identifiers', [])]} via={via!r} orig_name={e.get('original_name')!r}")
