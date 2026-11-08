"""Minimal RCON client for the local test server (enable-rcon=true in run-server/server.properties).

Usage:
  python tools/rcon.py <password> "command" ["command" ...]
  python tools/rcon.py <password> --script file.txt     (one command per line; "sleep N" waits N seconds)
"""
import socket
import struct
import sys
import time

HOST, PORT = "127.0.0.1", 25575


def packet(req_id, kind, payload):
    data = struct.pack("<ii", req_id, kind) + payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def recv(sock):
    raw = b""
    while len(raw) < 4:
        raw += sock.recv(4 - len(raw))
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        data += sock.recv(length - len(data))
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, data[8:-2].decode("utf-8", "replace")


def main():
    password = sys.argv[1]
    if sys.argv[2] == "--script":
        with open(sys.argv[3], encoding="utf-8") as f:
            commands = [l.strip() for l in f if l.strip() and not l.startswith("#")]
    else:
        commands = sys.argv[2:]
    with socket.create_connection((HOST, PORT), timeout=10) as s:
        s.sendall(packet(1, 3, password))
        req_id, _ = recv(s)
        if req_id == -1:
            sys.exit("RCON login failed")
        for i, cmd in enumerate(commands, start=2):
            if cmd.startswith("sleep "):
                time.sleep(float(cmd.split()[1]))
                continue
            s.sendall(packet(i, 2, cmd))
            _, out = recv(s)
            print(f"> {cmd}\n{out}" if out else f"> {cmd}")


if __name__ == "__main__":
    main()
