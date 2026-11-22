#!/usr/bin/env python3
"""Link interop driver: runs tests/fake_vm.py (SDK Noise responder) and
the Java LinkSession harness (tests/LinkMain) against each other over a
real WebSocket, and walks the chat / voice / TTS / unpair flow.

Usage: link_interop.py <classes-dir>
"""
import hashlib
import os
import queue
import subprocess
import sys
import threading
import time

FAKE_MP3 = b"\xff\xfb" + b"FAKEMP3AUDIO" * 40


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


class Proc:
    def __init__(self, argv, tag):
        self.tag = tag
        self.proc = subprocess.Popen(
            argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, bufsize=1)
        self.lines = queue.Queue()
        self.history = []
        threading.Thread(target=self._pump, args=(self.proc.stdout,), daemon=True).start()
        threading.Thread(target=self._pump_err, daemon=True).start()

    def _pump(self, stream):
        for line in stream:
            line = line.rstrip("\n")
            self.history.append(line)
            self.lines.put(line)

    def _pump_err(self):
        for line in self.proc.stderr:
            print(f"[{self.tag} err] {line}", end="", flush=True)

    def wait_line(self, needle: str, timeout: float = 60.0) -> str:
        deadline = time.time() + timeout
        for line in self.history:
            if needle in line:
                return line
        while time.time() < deadline:
            try:
                line = self.lines.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                break
            print(f"[{self.tag}] {line}", flush=True)
            if needle in line:
                return line
        raise AssertionError(
            f"{self.tag}: never saw {needle!r}; history: {self.history}; "
            f"exit={self.proc.poll()}")

    def send(self, line: str):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()


def main() -> int:
    classes_dir = sys.argv[1]
    port_file = "/tmp/fakevm-port.txt"
    if os.path.exists(port_file):
        os.remove(port_file)
    vm = Proc([sys.executable,
               os.path.join(os.path.dirname(__file__), "fake_vm.py"), port_file],
              "vm")
    vm.wait_line("LISTENING", timeout=15)
    port = int(open(port_file).read().strip())

    # The JVM may not open TCP in this sandbox, so the Java side runs
    # behind tests/ws_bridge.py (real WebSocket there, pipes to the JVM).
    client = Proc([sys.executable,
                   os.path.join(os.path.dirname(__file__), "ws_bridge.py"),
                   classes_dir, "vm-test-1", "test-vm-token",
                   f"ws://127.0.0.1:{port}"],
                  "java")
    try:
        vm.wait_line("READY_FOR_COMMANDS")
        client.wait_line("REGISTERED")
        print("PASS java LinkSession registered with the fake VM", flush=True)

        client.send("chat hello from shield")
        line = client.wait_line("CHAT_RESULT")
        assert "msg-42" in line, line
        vm.wait_line("PASS /chat/stream text turn answered")
        print("PASS chat turn round-tripped", flush=True)

        client.send("voice listen to this")
        line = client.wait_line("VOICE_RESULT")
        assert "msg-77" in line, line
        vm.wait_line("PASS /chat/stream voice turn (WAV item, voice modality)")
        print("PASS voice turn round-tripped", flush=True)

        client.send("tts msg-77")
        line = client.wait_line("TTS_RESULT")
        expected_sha = hashlib.sha256(FAKE_MP3).hexdigest()
        assert f"bytes={len(FAKE_MP3)}" in line, line
        assert expected_sha in line, (line, expected_sha)
        vm.wait_line("PASS tts-stream bytes delivered in two parts")
        print("PASS TTS bytes reassembled exactly (sha256 match)", flush=True)

        client.wait_line("EVENT UNPAIRED")
        client.wait_line("OUTCOME UNPAIRED")
        print("PASS link.unpaired ended the session as UNPAIRED", flush=True)

        vm.wait_line("FAKEVM RESULT: PASS")
        print("LINK INTEROP RESULT: PASS (Java LinkSession x SDK fake VM)")
        return 0
    finally:
        try:
            client.send("quit")
        except Exception:
            pass
        for proc in (client, vm):
            try:
                proc.proc.wait(timeout=10)
            except Exception:
                proc.proc.kill()


if __name__ == "__main__":
    sys.exit(main())
