package com.jonathancruzate.musegadgetshield;

/**
 * Multiplexes service frames over one post-handshake Noise session; a
 * port of the SDK's noise/transport.py, initiator side. Each service
 * frame is encrypted with the send cipher, wrapped in one or more
 * {@link NoiseFraming} chunks, and handed to {@link Sender} as raw Noise
 * messages for the WebSocket. Inbound messages decrypt into frames that
 * {@link Handler} receives. Any failure poisons the transport.
 */
public final class NoiseTransport {
    public static final int MAX_STREAM_ID = Integer.MAX_VALUE;

    public interface Sender {
        void sendMessage(byte[] message) throws Exception;
    }

    public interface Handler {
        void onRequest(long streamId, NoiseEnvelope.ApplicationRequest request);
        void onResponse(long streamId, NoiseEnvelope.ApplicationResponse response) throws Exception;
        void onBodyChunk(long streamId, NoiseEnvelope.BodyChunk chunk) throws Exception;
        void onReset(long streamId, NoiseEnvelope.Reset reset);
    }

    private final NoiseXx.CipherState sendCipher;
    private final NoiseXx.CipherState recvCipher;
    private final Sender sender;
    private final Handler handler;
    private final NoiseFraming.NoiseFrameDecoder decoder = new NoiseFraming.NoiseFrameDecoder();
    private int nextStreamId = 1;
    private boolean dead = false;

    public NoiseTransport(NoiseXx.CipherState sendCipher, NoiseXx.CipherState recvCipher,
            Sender sender, Handler handler) {
        this.sendCipher = sendCipher;
        this.recvCipher = recvCipher;
        this.sender = sender;
        this.handler = handler;
    }

    public synchronized int allocateStreamId() {
        assertAlive();
        int streamId = nextStreamId;
        nextStreamId += 1;
        if (nextStreamId > MAX_STREAM_ID) {
            dead = true;
            throw new IllegalStateException("NoiseTransport: stream id exhausted");
        }
        return streamId;
    }

    public synchronized void sendRequest(long streamId, NoiseEnvelope.ApplicationRequest request)
            throws Exception {
        assertAlive();
        sendPayload(NoiseEnvelope.encodeServiceRequest(
                new NoiseEnvelope.ServiceRequest(
                        NoiseEnvelope.SERVICE_DAEMON,
                        NoiseEnvelope.encodeServiceFrame(
                                NoiseEnvelope.ServiceFrame.request(streamId, request)))));
    }

    public synchronized void sendBodyChunk(long streamId, NoiseEnvelope.BodyChunk chunk) throws Exception {
        assertAlive();
        sendPayload(NoiseEnvelope.encodeServiceRequest(
                new NoiseEnvelope.ServiceRequest(
                        NoiseEnvelope.SERVICE_DAEMON,
                        NoiseEnvelope.encodeServiceFrame(
                                NoiseEnvelope.ServiceFrame.bodyChunk(streamId, chunk)))));
    }

    public synchronized void sendReset(long streamId, NoiseEnvelope.Reset reset) throws Exception {
        assertAlive();
        sendPayload(NoiseEnvelope.encodeServiceRequest(
                new NoiseEnvelope.ServiceRequest(
                        NoiseEnvelope.SERVICE_DAEMON,
                        NoiseEnvelope.encodeServiceFrame(
                                NoiseEnvelope.ServiceFrame.reset(streamId, reset)))));
    }

    public synchronized void handleMessage(byte[] message) throws Exception {
        assertAlive();
        byte[] plaintext;
        try {
            plaintext = recvCipher.decryptWithAd(new byte[0], message);
        } catch (RuntimeException e) {
            dead = true;
            throw e;
        }
        try {
            byte[] assembled = decoder.decode(plaintext);
            if (assembled == null) return;
            NoiseEnvelope.ServiceResponse response = NoiseEnvelope.decodeServiceResponse(assembled);
            NoiseEnvelope.ServiceFrame frame = NoiseEnvelope.decodeServiceFrame(response.payload);
            dispatchFrame(frame);
        } catch (Exception e) {
            dead = true;
            throw e;
        }
    }

    public boolean isDead() { return dead; }

    public void close() { dead = true; }

    private void sendPayload(byte[] plaintext) {
        try {
            for (byte[] frame : NoiseFraming.encodeNoiseFrames(plaintext)) {
                byte[] encrypted = sendCipher.encryptWithAd(new byte[0], frame);
                sender.sendMessage(encrypted);
            }
        } catch (Exception e) {
            dead = true;
            throw new IllegalStateException("NoiseTransport: send failed", e);
        }
    }

    private void dispatchFrame(NoiseEnvelope.ServiceFrame frame) throws Exception {
        if (frame.kind == null) return;
        switch (frame.kind) {
            case "request":
                handler.onRequest(frame.streamId, (NoiseEnvelope.ApplicationRequest) frame.value);
                break;
            case "response":
                handler.onResponse(frame.streamId, (NoiseEnvelope.ApplicationResponse) frame.value);
                break;
            case "body_chunk":
                handler.onBodyChunk(frame.streamId, (NoiseEnvelope.BodyChunk) frame.value);
                break;
            case "reset":
                handler.onReset(frame.streamId, (NoiseEnvelope.Reset) frame.value);
                break;
            default:
                break;
        }
    }

    private void assertAlive() {
        if (dead) throw new IllegalStateException("NoiseTransport: dead after failure");
    }
}
