#!/usr/bin/env python3
"""WebSocket bridge for the link interop test.

The sandbox this test runs in refuses raw TCP from the JVM, so this
bridge performs the real WebSocket connection (client side) and relays
binary messages to the Java LinkMain over inherited pipes (Java fd 3 =
bridge->Java, fd 4 = Java->bridge), framed as [4-byte length][payload].
The first Java frame is a JSON hello {url, authorization}; the bridge
answers "OK" or "ERR <http status>" as its first frame.

The bridge spawns LinkMain itself and passes its own stdin/stdout
through, so the driver talks to the bridge exactly like the Java
process.

Usage: ws_bridge.py <classes-dir> <vm-id> <token> <ws-url>
"""
import base64
import hashlib
import json
import os
import socket
import struct
import subprocess
import sys
import threading
from urllib.parse import urlparse


def java_bin() -> str:
    import glob
    import shutil
    found = shutil.which("java")
    if found:
        return found
    candidates = glob.glob(os.path.expanduser(
        "~/workspace/shield-wifi-sense/.toolchain/jdk*/bin/java"))
    if candidates:
        return candidates[0]
    raise RuntimeError("no java binary found")


def read_exact(sock, n: int) -> bytes:
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("socket closed")
        data += chunk
    return data


def read_ws_frame(sock):
    payload = b""
    while True:
        b0, b1 = read_exact(sock, 2)
        fin, opcode = b0 & 0x80, b0 & 0x0F
        masked = b1 & 0x80
        length = b1 & 0x7F
        if length == 126:
            (length,) = struct.unpack(">H", read_exact(sock, 2))
        elif length == 127:
            (length,) = struct.unpack(">Q", read_exact(sock, 8))
        mask = read_exact(sock, 4) if masked else None
        body = read_exact(sock, length) if length else b""
        if mask:
            body = bytes(b ^ mask[i % 4] for i, b in enumerate(body))
        if opcode == 0x9:
            send_ws_frame(sock, 0xA, body)
            continue
        if opcode == 0x8:
            raise ConnectionError("server closed the WebSocket")
        payload += body
        if fin:
            return payload


def send_ws_frame(sock, opcode: int, payload: bytes):
    mask = os.urandom(4)
    header = bytes([0x80 | opcode])
    length = len(payload)
    if length < 126:
        header += bytes([0x80 | length])
    elif length <= 0xFFFF:
        header += bytes([0x80 | 126]) + struct.pack(">H", length)
    else:
        header += bytes([0x80 | 127]) + struct.pack(">Q", length)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    sock.sendall(header + mask + masked)


def pipe_read(read_fd, n: int) -> bytes:
    data = b""
    while len(data) < n:
        chunk = os.read(read_fd, n - len(data))
        if not chunk:
            raise ConnectionError("pipe closed")
        data += chunk
    return data


def pipe_frame_read(read_fd) -> bytes:
    header = pipe_read(read_fd, 4)
    (length,) = struct.unpack(">I", header)
    return pipe_read(read_fd, length)


def pipe_frame_write(write_fd, payload: bytes):
    os.write(write_fd, struct.pack(">I", len(payload)) + payload)


def main() -> int:
    classes_dir, vm_id, token, ws_url = sys.argv[1:5]
    to_java_r, to_java_w = os.pipe()      # bridge writes, Java reads
    from_java_r, from_java_w = os.pipe()  # Java writes, bridge reads
    java = subprocess.Popen(
        [java_bin(), "-cp", classes_dir,
         "com.jonathancruzate.musegadgetshield.LinkMain",
         f"stdio:{to_java_r}:{from_java_w}", vm_id, token, ws_url],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=None,
        pass_fds=(to_java_r, from_java_w))
    os.close(to_java_r)
    os.close(from_java_w)

    # Relay the driver's stdin/stdout through to the Java process.
    def pump_stdin():
        try:
            while True:
                line = sys.stdin.buffer.read1(4096) if hasattr(sys.stdin.buffer, "read1") \
                    else sys.stdin.buffer.read(4096)
                if not line:
                    break
                java.stdin.write(line)
                java.stdin.flush()
        except Exception:
            pass

    def pump_stdout():
        try:
            while True:
                chunk = java.stdout.read1(4096) if hasattr(java.stdout, "read1") \
                    else java.stdout.read(4096)
                if not chunk:
                    break
                sys.stdout.buffer.write(chunk)
                sys.stdout.buffer.flush()
        except Exception:
            pass

    threading.Thread(target=pump_stdin, daemon=True).start()
    threading.Thread(target=pump_stdout, daemon=True).start()

    # The Java side speaks first: the hello frame.
    hello = json.loads(pipe_frame_read(from_java_r))
    url = urlparse(hello["url"])
    host = url.hostname
    port = url.port or 80
    path = url.path + ("?" + url.query if url.query else "")
    sock = socket.create_connection((host, port), timeout=20)
    key = base64.b64encode(os.urandom(16)).decode()
    request = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        f"Authorization: {hello['authorization']}\r\n"
        "\r\n")
    sock.sendall(request.encode("iso-8859-1"))
    response = b""
    while b"\r\n\r\n" not in response:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("server closed during upgrade")
        response += chunk
    status = int(response.split(b" ", 2)[1])
    if status != 101:
        pipe_frame_write(to_java_w, f"ERR {status}".encode())
        return 1
    accept = base64.b64encode(hashlib.sha1(
        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
    if accept.encode() not in response:
        pipe_frame_write(to_java_w, b"ERR -1")
        return 1
    pipe_frame_write(to_java_w, b"OK")

    stop = threading.Event()

    def tcp_to_java():
        try:
            while not stop.is_set():
                message = read_ws_frame(sock)
                pipe_frame_write(to_java_w, message)
        except Exception:
            stop.set()
            try:
                os.close(to_java_w)
            except OSError:
                pass

    def java_to_tcp():
        try:
            while not stop.is_set():
                message = pipe_frame_read(from_java_r)
                send_ws_frame(sock, 0x2, message)
        except Exception:
            stop.set()
            try:
                sock.close()
            except OSError:
                pass

    threading.Thread(target=tcp_to_java, daemon=True).start()
    relay = threading.Thread(target=java_to_tcp, daemon=True)
    relay.start()
    relay.join(timeout=120)
    stop.set()
    try:
        java.wait(timeout=10)
    except Exception:
        java.kill()
    sys.stdout.buffer.flush()
    # Skip interpreter finalization: the daemon stdin pump can otherwise
    # abort the process while the buffered reader is busy.
    os._exit(0)


if __name__ == "__main__":
    sys.exit(main())
