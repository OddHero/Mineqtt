"""Spike check: Minecraft server list ping against a lane's published game port (what a host client sees first).

  python slp_ping.py <port>
"""
import json
import socket
import struct
import sys


def varint(value):
    out = b""
    while True:
        byte = value & 0x7F
        value >>= 7
        out += bytes([byte | (0x80 if value else 0)])
        if not value:
            return out


def read_varint(sock):
    result = shift = 0
    while True:
        byte = sock.recv(1)[0]
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result
        shift += 7


def packet(payload):
    return varint(len(payload)) + payload


def main():
    port = int(sys.argv[1])
    with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
        host = b"127.0.0.1"
        # Handshake (id 0): protocol 0 (servers answer status for any protocol), address, port, next state 1 (status);
        # then the status request.
        sock.sendall(packet(varint(0) + varint(0) + varint(len(host)) + host
                            + struct.pack(">H", port) + varint(1)))
        sock.sendall(packet(varint(0)))
        read_varint(sock)  # packet length
        read_varint(sock)  # packet id
        length = read_varint(sock)
        data = b""
        while len(data) < length:
            data += sock.recv(length - len(data))
    status = json.loads(data)
    print(json.dumps({"version": status.get("version"), "players": status.get("players"),
                      "description": status.get("description")}))


if __name__ == "__main__":
    main()
