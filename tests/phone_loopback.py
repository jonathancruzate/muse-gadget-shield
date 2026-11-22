#!/usr/bin/env python3
"""Cross-implementation pairing loopback test.

Drives the Java device-side SetupController + PairingSession (running as
a subprocess, tests/LoopbackDevice) with a phone implemented from the
SDK's own pairing code (build_transcript / derive_session_keys imported
from the SDK) plus fresh P-256 keys. Exercises the whole v5 setup flow:
plaintext guards, get_device_info, hello, encrypted client_finished,
wifi_scan, provision_v2, and the persisted pairing.json.

Usage: phone_loopback.py <classes-dir> <state-dir>
"""
import base64
import hashlib
import json
import os
import struct
import subprocess
import sys
import threading

sys.path.insert(0, os.path.expanduser("~/workspace/muse-gadget-sdk/linux/src"))

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from musegadget.ble_framing import ChunkAssembler, encode_chunks
from musegadget.pairing import build_transcript, derive_session_keys


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def pub_bytes(key) -> bytes:
    numbers = key.public_key().public_numbers()
    return b"\x04" + numbers.x.to_bytes(32, "big") + numbers.y.to_bytes(32, "big")


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


class Device:
    def __init__(self, classes_dir: str, state_dir: str):
        self.proc = subprocess.Popen(
            [java_bin(), "-cp", classes_dir,
             "com.jonathancruzate.musegadgetshield.LoopbackDevice", state_dir],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE)
        self.stderr_lines = []
        self._stderr_thread = threading.Thread(
            target=self._drain_stderr, daemon=True)
        self._stderr_thread.start()
        self.assembler = ChunkAssembler()
        self._messages = []
        self._cond = threading.Condition()
        self._reader = threading.Thread(target=self._read_packets, daemon=True)
        self._reader.start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            self.stderr_lines.append(line.decode(errors="replace").rstrip())

    def _read_packets(self):
        while True:
            header = self.proc.stdout.read(4)
            if len(header) < 4:
                return
            (length,) = struct.unpack(">I", header)
            packet = self.proc.stdout.read(length)
            if len(packet) < length:
                return
            message = self.assembler.feed(packet)
            if message is not None:
                with self._cond:
                    self._messages.append(message)
                    self._cond.notify_all()

    def wait_stderr(self, needle: str, timeout: float = 10.0):
        import time
        deadline = time.time() + timeout
        while time.time() < deadline:
            if any(needle in line for line in self.stderr_lines):
                return True
            time.sleep(0.05)
        return False

    def send_message(self, payload: bytes):
        for packet in encode_chunks(payload, mtu=517):
            self.proc.stdin.write(struct.pack(">I", len(packet)) + packet)
        self.proc.stdin.flush()

    def send_json(self, obj):
        self.send_message(json.dumps(obj).encode())

    def next_message(self, timeout: float = 15.0) -> bytes:
        with self._cond:
            if not self._cond.wait_for(lambda: self._messages, timeout):
                raise TimeoutError("device sent nothing; stderr: "
                                   + " | ".join(self.stderr_lines[-5:]))
            return self._messages.pop(0)

    def next_json(self, timeout: float = 15.0):
        return json.loads(self.next_message(timeout))

    def close(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        self.proc.wait(timeout=10)


class Phone:
    """The mobile side, keyed fresh; crypto checked against the SDK."""

    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.nonce = os.urandom(16)
        self.tx_counter = 0
        self.rx_counter = 0
        self.tx = None
        self.rx = None
        self.session_id = None

    def hello(self) -> dict:
        return {
            "action": "pairing_client_hello",
            "version": 5,
            "pairing_auth": "none",
            "pairing_policy": "confirm_app",
            "mobile_pub": b64(pub_bytes(self.key)),
            "mobile_nonce": b64(self.nonce),
        }

    def accept_ready(self, ready: dict):
        transcript = build_transcript(
            community=True, auth_epoch=0, policy="confirm_app",
            device_id=ready["device_id"], node_id=ready["node_id"],
            mac=ready["mac"], firmware_version=ready["firmware_version"],
            mobile_pub=b64(pub_bytes(self.key)),
            device_pub=ready["device_pub"],
            mobile_nonce=b64(self.nonce),
            device_nonce=ready["device_nonce"])
        transcript_hash = hashlib.sha256(transcript.encode()).digest()
        assert b64(transcript_hash) == ready["transcript_hash"], \
            "transcript_hash mismatch: SDK phone computed a different transcript"
        peer = ec.EllipticCurvePublicKey.from_encoded_point(
            ec.SECP256R1(), unb64(ready["device_pub"]))
        secret = self.key.exchange(ec.ECDH(), peer)
        mobile_tx, mobile_rx, session_id = derive_session_keys(
            secret, self.nonce, unb64(ready["device_nonce"]), transcript_hash)
        assert b64(session_id) == ready["session_id"], "session_id mismatch"
        self.tx = AESGCM(mobile_tx)
        self.rx = AESGCM(mobile_rx)
        self.session_id = ready["session_id"]
        print("PASS phone derived the session the SDK way; "
              "transcript_hash + session_id match the Java device")

    def _nonce(self, direction: int, counter: int) -> bytes:
        return bytes([direction]) + b"\0\0\0" + counter.to_bytes(8, "big")

    def _aad(self, arrow: str, counter: int) -> bytes:
        return f"hatch-link ble setup v1|{self.session_id}|{arrow}|{counter}".encode()

    def seal(self, command: dict) -> dict:
        counter = self.tx_counter
        self.tx_counter += 1
        sealed = self.tx.encrypt(self._nonce(0, counter),
                                 json.dumps(command).encode(),
                                 self._aad("m2d", counter))
        return {
            "action": "pairing_encrypted",
            "session_id": self.session_id,
            "counter": str(counter),
            "ciphertext": b64(sealed[:-16]),
            "tag": b64(sealed[-16:]),
        }

    def open(self, envelope: dict) -> dict:
        assert envelope["type"] == "pairing_encrypted", envelope
        assert envelope["session_id"] == self.session_id
        counter = int(envelope["counter"])
        assert counter == self.rx_counter, f"device counter {counter} != {self.rx_counter}"
        self.rx_counter += 1
        plain = self.rx.decrypt(self._nonce(1, counter),
                                unb64(envelope["ciphertext"]) + unb64(envelope["tag"]),
                                self._aad("d2m", counter))
        return json.loads(plain)


def main() -> int:
    classes_dir, state_dir = sys.argv[1], sys.argv[2]
    device = Device(classes_dir, state_dir)
    try:
        assert device.wait_stderr("READY"), "device never became ready"
        print("PASS device up:", [l for l in device.stderr_lines if "READY" in l][0])

        # 1. Sensitive action in plaintext is refused before any hello.
        device.send_json({"action": "wifi_scan"})
        refusal = device.next_message().decode()
        assert refusal == "error_encryption_required", refusal
        print("PASS plaintext wifi_scan refused:", refusal)

        # 2. get_device_info in plaintext.
        device.send_json({"action": "get_device_info"})
        info = device.next_json()
        assert info["type"] == "device_info", info
        assert info["node_id"] == "homelink-fa76f8", info
        assert info["pairing_protocol"] == 5, info
        assert info["pairing_auth"] == "none", info
        assert info["pairing_policy"] == "confirm_app", info
        print("PASS get_device_info:", info["node_id"], "protocol",
              info["pairing_protocol"])

        # 3. Hello -> ready, cross-checked against SDK transcript code.
        phone = Phone()
        device.send_json(phone.hello())
        ready = device.next_json()
        assert ready["type"] == "pairing_ready", ready
        phone.accept_ready(ready)

        # 4. Encrypted client_finished -> pairing_confirmed.
        device.send_json(phone.seal({"action": "pairing_client_finished"}))
        confirmed = phone.open(device.next_json())
        assert confirmed == {"type": "status", "status": "pairing_confirmed"}, confirmed
        print("PASS pairing_confirmed opened with the SDK-derived key")

        # 5. wifi_scan -> the single current-connection entry.
        device.send_json(phone.seal({"action": "wifi_scan"}))
        scan = phone.open(device.next_json())
        assert scan["type"] == "wifi_scan_result", scan
        assert len(scan["networks"]) == 1, scan
        assert scan["networks"][0]["ssid"] == "Use current connection", scan
        assert scan["networks"][0]["secure"] is False, scan
        print("PASS wifi_scan offered:", scan["networks"][0])

        # 6. provision_v2 -> wifi_connecting, wifi_connected, auth_ok.
        device.send_json(phone.seal({
            "action": "provision_v2",
            "ssid": "Use current connection",
            "password": "",
            "access_token": "test-access-token",
            "refresh_token": "test-refresh-token",
            "token_type": "device",
            "username": "loopback-tester",
        }))
        seen = []
        for _ in range(3):
            status = phone.open(device.next_json())
            assert status["type"] == "status", status
            seen.append(status["status"])
        assert seen == ["wifi_connecting", "wifi_connected", "auth_ok"], seen
        print("PASS provisioning statuses:", " -> ".join(seen))

        # 7. The pairing record landed on disk with the credentials.
        pairing_file = os.path.join(state_dir, "pairing.json")
        assert os.path.exists(pairing_file), "pairing.json was not persisted"
        record = json.load(open(pairing_file))
        assert record["access_token"] == "test-access-token", record
        assert record["refresh_token"] == "test-refresh-token", record
        assert record["token_type"] == "device", record
        assert record["username"] == "loopback-tester", record
        assert device.wait_stderr("PROVISIONED"), "onComplete never ran"
        print("PASS pairing.json persisted:", pairing_file)

        print("LOOPBACK RESULT: PASS (full pairing v5 flow, Java device x SDK phone)")
        return 0
    finally:
        device.close()


if __name__ == "__main__":
    sys.exit(main())
