package com.jonathancruzate.musegadgetshield;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Desktop harness: the production SetupController + PairingSession driven
 * over stdio. Packets are framed as [4-byte big-endian length][bytes] on
 * stdout (device -> phone) and stdin (phone -> device). Control markers
 * go to stderr. Run by tests/phone_loopback.py.
 */
public final class LoopbackDevice {
    public static void main(String[] args) throws Exception {
        final File stateDir = new File(args[0]);
        stateDir.mkdirs();
        final OutputStream out = System.out;
        final Object writeLock = new Object();

        Identity identity = Identity.loadOrCreate(stateDir, "FA76F8", new SecureRandom());
        Pairing.PairingSession pairing = new Pairing.PairingSession(
                identity.nodeId(), identity.deviceId(), identity.mac, "shield-0.4.0-test", null);

        SetupController.Transport transport = new SetupController.Transport() {
            @Override public void sendPackets(List<byte[]> packets) {
                synchronized (writeLock) {
                    try {
                        for (byte[] packet : packets) {
                            out.write((packet.length >>> 24) & 0xFF);
                            out.write((packet.length >>> 16) & 0xFF);
                            out.write((packet.length >>> 8) & 0xFF);
                            out.write(packet.length & 0xFF);
                            out.write(packet);
                        }
                        out.flush();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            @Override public int mtu() { return 517; }
            @Override public void disconnect(double delaySeconds) {
                System.err.println("TRANSPORT_DISCONNECT delay=" + delaySeconds);
            }
        };
        SetupController.Network network = new SetupController.Network() {
            @Override public boolean isOnline() { return true; }
            @Override public Map<String, Object> currentConnectionEntry() {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("ssid", "Use current connection");
                entry.put("rssi", -40L);
                entry.put("secure", Boolean.FALSE);
                return entry;
            }
        };
        SetupController.Provision provision = new SetupController.Provision() {
            @Override public void provision(SetupController.Credentials credentials,
                    final SetupController.Committer commit) throws SetupController.ProvisionFailed {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("access_token", credentials.accessToken);
                record.put("refresh_token", credentials.refreshToken);
                record.put("token_type", "device");
                record.put("username", credentials.username != null ? credentials.username : "");
                record.put("api_url", credentials.apiUrl != null
                        && credentials.apiUrl.startsWith("https://") ? credentials.apiUrl : "");
                record.put("api_url_v2", credentials.apiUrlV2 != null
                        && credentials.apiUrlV2.startsWith("https://") ? credentials.apiUrlV2 : "");
                record.put("noise_host", credentials.noiseHost != null ? credentials.noiseHost : "");
                record.put("access_token_saved_at", System.currentTimeMillis() / 1000);
                record.put("sdk_token_hint", "none");
                final Map<String, Object> toSave = record;
                boolean committed = commit.commit(new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        DeviceStore.saveJson(new File(stateDir, DeviceStore.PAIRING_FILE), toSave);
                        return true;
                    }
                });
                if (!committed) throw new SetupController.ProvisionFailed("auth_failed");
            }
        };
        final SetupController controller = new SetupController(
                pairing, identity, "0.4.0-test", transport, network, provision,
                new Runnable() {
                    @Override public void run() { System.err.println("PROVISIONED"); }
                });
        controller.start();
        System.err.println("READY node=" + identity.nodeId());

        DataInputStream in = new DataInputStream(System.in);
        while (true) {
            int length;
            try {
                length = in.readInt();
            } catch (IOException e) {
                break;
            }
            if (length <= 0 || length > 65536) break;
            byte[] packet = new byte[length];
            in.readFully(packet);
            controller.onWrite(packet);
        }
        controller.stop();
    }
}
