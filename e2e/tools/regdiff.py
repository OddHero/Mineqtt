"""regdiff.py <dump.json> [unique_id...]: entity registry entries (id, entity_id, device) for the given unique_ids."""
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
devices = {x["id"]: x for x in d["devices"]}
states = {s["entity_id"]: s["state"] for s in d["states"]}
for e in d["entities"]:
    if e["platform"] == "mqtt" and (len(sys.argv) < 3 or e["unique_id"] in sys.argv[2:]):
        dev = devices.get(e.get("device_id"), {})
        via = devices.get(dev.get("via_device_id"), {}).get("name")
        print(f"{e['unique_id']:14} reg_id={e['id']} entity_id={e['entity_id']} state={states.get(e['entity_id'])} "
              f"device={dev.get('name')!r} ids={[i[1] for i in dev.get('identifiers', [])]} via={via!r} "
              f"orig_name={e.get('original_name')!r}")
