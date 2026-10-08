"""Sends an MQTT 5 CONNECT to host:port and prints the raw reply bytes."""
import socket, sys
host, port = sys.argv[1], int(sys.argv[2])
client_id = b"probe"
# variable header: protocol name, level 5, flags (clean start), keep alive 60, properties length 0
var = b"\x00\x04MQTT" + b"\x05" + b"\x02" + b"\x00\x3c" + b"\x00"
payload = len(client_id).to_bytes(2, "big") + client_id
body = var + payload
packet = bytes([0x10, len(body)]) + body
s = socket.create_connection((host, port), timeout=5)
s.sendall(packet)
try:
    print(s.recv(64).hex(" "))
except Exception as e:
    print("error", e)
