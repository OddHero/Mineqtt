"""Minimal Minecraft RCON client: rcon.py <port> <password> <command...>"""
import socket
import struct
import sys


def packet(request_id, kind, body):
    payload = struct.pack('<ii', request_id, kind) + body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(payload)) + payload


def receive(sock):
    length = struct.unpack('<i', sock.recv(4))[0]
    data = b''
    while len(data) < length:
        data += sock.recv(length - len(data))
    request_id, kind = struct.unpack('<ii', data[:8])
    return request_id, data[8:-2].decode('utf-8', 'replace')


def main():
    port, password, command = int(sys.argv[1]), sys.argv[2], ' '.join(sys.argv[3:])
    with socket.create_connection(('127.0.0.1', port), timeout=10) as sock:
        sock.sendall(packet(1, 3, password))
        if receive(sock)[0] == -1:
            sys.exit('auth failed')
        sock.sendall(packet(2, 2, command))
        print(receive(sock)[1])


if __name__ == '__main__':
    main()
