package com.jonathancruzate.musegadgetshield;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop harness for LinkSession against tests/fake_vm.py.
 * Args: <noise-host e.g. ws://127.0.0.1:PORT> <vm-id> <vm-auth-token>.
 * Commands on stdin: "chat <text>", "voice <text>", "tts <message-id>", "quit".
 * Results on stdout; logs on stderr.
 */
public final class LinkMain {
    /**
     * A WebSocket connection bridged over inherited pipe fds by
     * tests/ws_bridge.py, for sandboxes where the JVM may not open TCP
     * sockets itself. Frames are [4-byte length][payload]; the first
     * frame out is a JSON hello (url + authorization), the first frame
     * in is "OK" or "ERR <http status>".
     */
    static final class StdioConnection implements WsConnection {
        private final java.io.InputStream in;
        private final java.io.OutputStream out;
        private final WsClient.Listener listener;
        private volatile boolean closed = false;

        StdioConnection(java.net.URI url, java.util.Map<String, String> headers,
                WsClient.Listener listener, int readFd, int writeFd)
                throws java.io.IOException {
            this.listener = listener;
            this.in = new java.io.FileInputStream("/proc/self/fd/" + readFd);
            this.out = new java.io.FileOutputStream("/proc/self/fd/" + writeFd);
            java.util.Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("url", url.toString());
            hello.put("authorization", headers.get("Authorization"));
            writeFrame(Json.stringify(hello).getBytes(StandardCharsets.UTF_8));
            byte[] first = readFrame();
            String text = new String(first, StandardCharsets.UTF_8);
            if (text.startsWith("ERR ")) {
                int status = Integer.parseInt(text.substring(4).trim());
                throw new WsClient.UpgradeRejectedException(
                        status, "WebSocket upgrade rejected with HTTP " + status);
            }
            if (!text.equals("OK")) {
                throw new java.io.IOException("bridge handshake failed: " + text);
            }
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        while (!closed) {
                            byte[] message = readFrame();
                            if (message == null) break;
                            listener.onMessage(message);
                        }
                    } catch (java.io.IOException ignored) { }
                    if (!closed) listener.onClosed("bridge closed");
                }
            }, "stdio-ws-reader");
            reader.setDaemon(true);
            reader.start();
        }

        private synchronized void writeFrame(byte[] payload) throws java.io.IOException {
            out.write((payload.length >>> 24) & 0xFF);
            out.write((payload.length >>> 16) & 0xFF);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
            out.write(payload);
            out.flush();
        }

        private byte[] readFrame() throws java.io.IOException {
            byte[] header = readN(4);
            if (header == null) return null;
            int length = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                    | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            byte[] payload = readN(length);
            if (payload == null) throw new java.io.EOFException("bridge frame truncated");
            return payload;
        }

        private byte[] readN(int n) throws java.io.IOException {
            byte[] data = new byte[n];
            int offset = 0;
            while (offset < n) {
                int read = in.read(data, offset, n - offset);
                if (read == -1) return null;
                offset += read;
            }
            return data;
        }

        @Override public void send(byte[] payload) throws java.io.IOException {
            if (closed) throw new java.io.IOException("bridge connection is closed");
            writeFrame(payload);
        }

        @Override public void close() {
            closed = true;
            try { out.close(); } catch (java.io.IOException ignored) { }
            try { in.close(); } catch (java.io.IOException ignored) { }
        }
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        String vmId = args[1];
        String token = args[2];
        LinkSession.Connector connector = LinkSession.defaultConnector();
        if (host.startsWith("stdio:")) {
            String[] fds = host.substring("stdio:".length()).split(":");
            final int readFd = Integer.parseInt(fds[0]);
            final int writeFd = Integer.parseInt(fds[1]);
            host = args[3];
            connector = new LinkSession.Connector() {
                @Override public WsConnection connect(java.net.URI url,
                        java.util.Map<String, String> headers,
                        WsClient.Listener listener) throws java.io.IOException {
                    return new StdioConnection(url, headers, listener, readFd, writeFd);
                }
            };
        }

        List<LinkSession.Command> commands = new ArrayList<>();
        commands.add(new LinkSession.Command(
                "device.info", "Report device identity.", "device.info",
                new LinkSession.CommandHandler() {
                    @Override public Map<String, Object> handle(Map<String, Object> params) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("node_id", "homelink-fa76f8");
                        info.put("platform", "android-tv");
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("ok", Boolean.TRUE);
                        result.put("payload", info);
                        return result;
                    }
                }));

        final LinkSession session = new LinkSession(
                vmId, token, host, "homelink-fa76f8", "Shield TV", commands,
                new LinkSession.Callbacks() {
                    @Override public void log(String message) {
                        System.err.println("LINK " + message);
                    }
                    @Override public void onUnpaired() {
                        System.out.println("EVENT UNPAIRED");
                    }
                }, connector);

        Thread runner = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    session.open();
                    System.out.println("REGISTERED");
                    LinkSession.Outcome outcome = session.run();
                    System.out.println("OUTCOME " + outcome);
                } catch (LinkSession.LinkError e) {
                    System.out.println("LINKERROR " + e.kind + " " + e.getMessage());
                }
            }
        }, "link-main");
        runner.setDaemon(true);
        runner.start();

        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            try {
                if (line.startsWith("chat ")) {
                    List<Map<String, Object>> reply =
                            session.sendChat(line.substring(5), null);
                    System.out.println("CHAT_RESULT " + Json.stringify(reply));
                } else if (line.startsWith("voice ")) {
                    byte[] wav = tinyWav();
                    List<Map<String, Object>> reply = session.sendVoice(
                            wav, null, line.substring(6), "voice", 60);
                    System.out.println("VOICE_RESULT " + Json.stringify(reply));
                } else if (line.startsWith("tts ")) {
                    byte[] audio = session.streamTts(line.substring(4));
                    System.out.println("TTS_RESULT bytes=" + audio.length
                            + " sha256=" + B64.hex(Hashes.sha256(audio)));
                } else if (line.equals("quit")) {
                    break;
                }
            } catch (Exception e) {
                System.out.println("COMMAND_ERROR " + e);
            }
        }
        session.close();
    }

    /** A minimal valid WAV: 0.1 s of silence, 24 kHz mono PCM16. */
    private static byte[] tinyWav() {
        int samples = 2400;
        int dataBytes = samples * 2;
        byte[] wav = new byte[44 + dataBytes];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, wav, 0, 4);
        put32(wav, 4, 36 + dataBytes);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, wav, 8, 4);
        System.arraycopy("fmt ".getBytes(StandardCharsets.US_ASCII), 0, wav, 12, 4);
        put32(wav, 16, 16);
        put16(wav, 20, 1);      // PCM
        put16(wav, 22, 1);      // mono
        put32(wav, 24, 24000);  // sample rate
        put32(wav, 28, 48000);  // byte rate
        put16(wav, 32, 2);      // block align
        put16(wav, 34, 16);     // bits per sample
        System.arraycopy("data".getBytes(StandardCharsets.US_ASCII), 0, wav, 36, 4);
        put32(wav, 40, dataBytes);
        return wav;
    }

    private static void put32(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >>> 8);
        b[offset + 2] = (byte) (value >>> 16);
        b[offset + 3] = (byte) (value >>> 24);
    }

    private static void put16(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >>> 8);
    }
}
