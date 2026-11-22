#!/usr/bin/env python3
"""A minimal Muse VM for interop testing: a raw-socket WebSocket server
plus the SDK's own Noise responder, envelope and transport code. Mirrors
tests/test_link_client.py's FakeVm, over a real socket so the Java
LinkSession can connect.

Scenario (asserts at each step, prints PASS lines):
  handshake -> control stream opens -> link.register -> register ACK ->
  invoke device.info -> invoke unknown command -> /chat/stream POST ->
  voice /chat/stream POST -> TTS stream -> link.unpaired event.

Usage: fake_vm.py <port-file>
"""
import base64
import hashlib
import json
import os
import socket
import struct
import sys

sys.path.insert(0, os.path.expanduser("~/workspace/muse-gadget-sdk/linux/src"))

from musegadget.link_client import MessageDecoder, encode_message
from musegadget.noise.envelope import (
    ApplicationResponse, BodyChunk, ServiceFrame)
from musegadget.noise.framing import NoiseFrameDecoder, encode_noise_frames
from musegadget.noise.noise_xx import NoiseXXResponder
from musegadget.noise.transport import decode_request_envelope, encode_response_envelope

EXPECTED_TOKEN = "test-vm-token"
FAKE_MP3 = b"\xff\xfb" + b"FAKEMP3AUDIO" * 40  # 482 bytes of stand-in audio


def read_http_request(conn) -> dict:
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(4096)
        if not chunk:
            raise ConnectionError("client closed during upgrade")
        data += chunk
    head = data.split(b"\r\n\r\n", 1)[0].decode("iso-8859-1")
    lines = head.split("\r\n")
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key.strip().lower()] = value.strip()
    return headers


def ws_accept(key: str) -> str:
    digest = hashlib.sha1(
        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
    return base64.b64encode(digest).decode()


def read_exact(conn, n: int) -> bytes:
    data = b""
    while len(data) < n:
        chunk = conn.recv(n - len(data))
        if not chunk:
            raise ConnectionError("socket closed")
        data += chunk
    return data


def read_frame(conn):
    """Returns (opcode, payload); assembles fragments."""
    payload = b""
    while True:
        b0, b1 = read_exact(conn, 2)
        fin = b0 & 0x80
        opcode = b0 & 0x0F
        masked = b1 & 0x80
        length = b1 & 0x7F
        if length == 126:
            (length,) = struct.unpack(">H", read_exact(conn, 2))
        elif length == 127:
            (length,) = struct.unpack(">Q", read_exact(conn, 8))
        mask = read_exact(conn, 4) if masked else None
        body = read_exact(conn, length) if length else b""
        if mask:
            body = bytes(b ^ mask[i % 4] for i, b in enumerate(body))
        if opcode == 0x9:  # ping -> pong, keep reading
            send_frame(conn, 0xA, body)
            continue
        if opcode == 0x8:
            raise ConnectionError("client closed the WebSocket")
        payload += body
        if fin:
            return opcode, payload


def send_frame(conn, opcode: int, payload: bytes):
    header = bytes([0x80 | opcode])
    length = len(payload)
    if length < 126:
        header += bytes([length])
    elif length <= 0xFFFF:
        header += bytes([126]) + struct.pack(">H", length)
    else:
        header += bytes([127]) + struct.pack(">Q", length)
    conn.sendall(header + payload)


class Vm:
    def __init__(self, conn):
        self.conn = conn
        self.send_cipher = None
        self.recv_cipher = None
        self.frame_decoder = NoiseFrameDecoder()
        self.control_stream_id = None
        self.decoder = MessageDecoder()

    def handshake(self):
        responder = NoiseXXResponder()
        responder.initialize()
        msg1 = read_frame(self.conn)[1]
        send_frame(self.conn, 0x2, responder.read_message1_and_write_message2(msg1))
        msg3 = read_frame(self.conn)[1]
        responder.read_message3(msg3)
        self.send_cipher, self.recv_cipher = responder.split()
        print("PASS noise handshake completed (SDK responder x Java initiator)", flush=True)

    def next_frame(self):
        # Exactly the SDK test FakeVm's receive path.
        while True:
            plain = self.recv_cipher.decrypt_with_ad(b"", read_frame(self.conn)[1])
            assembled = self.frame_decoder.decode(plain)
            if assembled is not None:
                return decode_request_envelope(assembled)

    def send_service_frame(self, frame: ServiceFrame):
        for chunk in encode_noise_frames(encode_response_envelope(frame)):
            send_frame(self.conn, 0x2, self.send_cipher.encrypt_with_ad(b"", chunk))

    def next_control_message(self) -> dict:
        while True:
            frame = self.next_frame()
            assert frame.stream_id == self.control_stream_id, frame
            if frame.kind == "response":
                data = frame.value.body
            else:
                data = frame.value.data
            messages = self.decoder.feed(data)
            if messages:
                return messages[0]

    def send_control_message(self, message: dict):
        self.send_service_frame(ServiceFrame.body_chunk(
            self.control_stream_id, BodyChunk(data=encode_message(message))))

    def next_request(self):
        """Collect one HTTP request (request frame + body chunks)."""
        frame = self.next_frame()
        assert frame.kind == "request", frame
        stream_id = frame.stream_id
        verb, path = frame.value.verb, frame.value.path
        body = frame.value.body
        ended = frame.value.end_body
        while not ended:
            frame = self.next_frame()
            assert frame.stream_id == stream_id, frame
            if frame.kind == "body_chunk":
                body += frame.value.data
                ended = frame.value.end_body
            elif frame.kind == "response":
                continue
        return stream_id, verb, path, body


def main() -> int:
    port_file = sys.argv[1]
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]
    with open(port_file, "w") as fh:
        fh.write(str(port))
    print(f"LISTENING {port}", flush=True)
    conn, _ = server.accept()
    conn.settimeout(60)
    try:
        headers = read_http_request(conn)
        assert headers.get("authorization") == f"Bearer {EXPECTED_TOKEN}", headers
        response = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {ws_accept(headers['sec-websocket-key'])}\r\n"
            "\r\n")
        conn.sendall(response.encode("iso-8859-1"))
        print("PASS websocket upgrade with the VM bearer token", flush=True)

        vm = Vm(conn)
        vm.handshake()

        # Control stream opens with a POST /link-control request frame.
        frame = vm.next_frame()
        assert frame.kind == "request", frame
        assert (frame.value.verb, frame.value.path) == ("POST", "/link-control"), frame.value
        vm.control_stream_id = frame.stream_id
        vm.send_service_frame(ServiceFrame.response(
            vm.control_stream_id, ApplicationResponse(status=200, end_body=False)))
        print("PASS control stream opened: POST /link-control", flush=True)

        register = vm.next_control_message()
        assert register["method"] == "link.register", register
        params = register["params"]
        assert params["node_id"] == "homelink-fa76f8", params
        assert params["platform"] == "linux", params
        assert params["device_family"] == "homehub", params
        assert params["model_id"] == "linux", params
        assert params["is_wakeup_supported"] is False, params
        names = [c["name"] for c in params["commands_v2"]]
        assert "device.info" in names, names
        print("PASS link.register params (linux/homehub, device.info advertised)", flush=True)
        vm.send_control_message({"type": "res", "id": register["id"], "ok": True})
        print("READY_FOR_COMMANDS", flush=True)

        # Invoke device.info.
        vm.send_control_message({
            "method": "link.invoke", "id": "inv-1",
            "command": "device.info", "params": {}})
        result = vm.next_control_message()
        assert result["method"] == "link.result", result
        assert result["id"] == "inv-1", result
        assert result["ok"] is True, result
        assert result["payload"]["node_id"] == "homelink-fa76f8", result
        print("PASS link.invoke device.info -> link.result ok with payload", flush=True)

        # Invoke an unknown command.
        vm.send_control_message({
            "method": "link.invoke", "id": "inv-2",
            "command": "nope", "params": {}})
        result = vm.next_control_message()
        assert result["ok"] is False, result
        assert "unknown command" in result["error"], result
        print("PASS unknown invoke -> link.result error:", result["error"], flush=True)

        # Chat POST.
        stream_id, verb, path, body = vm.next_request()
        assert (verb, path) == ("POST", "/chat/stream"), (verb, path)
        chat = json.loads(body)
        assert chat["message"] == "hello from shield", chat
        assert chat["device_id"] == "homelink-fa76f8", chat
        vm.send_service_frame(ServiceFrame.response(
            stream_id, ApplicationResponse(
                status=200,
                body=b'{"type":"chat_reply","id":"msg-42","text":"hi"}',
                end_body=True)))
        print("PASS /chat/stream text turn answered", flush=True)

        # Voice POST.
        stream_id, verb, path, body = vm.next_request()
        assert (verb, path) == ("POST", "/chat/stream"), (verb, path)
        voice = json.loads(body)
        assert voice["output_modality"] == "voice", voice
        items = voice["items"]
        assert items[0] == {"type": "text", "text": "listen to this"}, items
        assert items[1]["type"] == "file", items
        assert items[1]["mime_type"] == "audio/wav", items
        wav = base64.b64decode(items[1]["data_base64"])
        assert wav[:4] == b"RIFF" and len(wav) < 2 * 1024 * 1024, len(wav)
        vm.send_service_frame(ServiceFrame.response(
            stream_id, ApplicationResponse(
                status=200,
                body=b'{"type":"chat_reply","id":"msg-77","text":"spoken reply"}',
                end_body=True)))
        print("PASS /chat/stream voice turn (WAV item, voice modality)", flush=True)

        # TTS stream for the voice reply, delivered in two parts.
        stream_id, verb, path, body = vm.next_request()
        assert verb == "GET", (verb, path)
        assert path.startswith("/api/voice/tts-stream?message_id=msg-77"), path
        half = len(FAKE_MP3) // 2
        vm.send_service_frame(ServiceFrame.response(
            stream_id, ApplicationResponse(
                status=200, body=FAKE_MP3[:half], end_body=False)))
        vm.send_service_frame(ServiceFrame.body_chunk(
            stream_id, BodyChunk(data=FAKE_MP3[half:], end_body=True)))
        print("PASS tts-stream bytes delivered in two parts", flush=True)

        # Unpair from the VM side.
        vm.send_control_message({"type": "evt", "event": "link.unpaired"})
        print("PASS link.unpaired event sent", flush=True)

        # The client should close after the unpair outcome.
        try:
            read_frame(conn)
        except (ConnectionError, socket.timeout, OSError):
            pass
        print("FAKEVM RESULT: PASS", flush=True)
        return 0
    finally:
        try:
            conn.close()
        except Exception:
            pass
        server.close()


if __name__ == "__main__":
    sys.exit(main())
