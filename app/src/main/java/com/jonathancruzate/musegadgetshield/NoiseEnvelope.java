package com.jonathancruzate.musegadgetshield;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Service envelopes carried inside Noise transport payloads; a port of
 * the SDK's noise/envelope.py (Header, ApplicationRequest/Response,
 * BodyChunk, Reset, ServiceFrame, ServiceRequest/Response).
 */
public final class NoiseEnvelope {
    private NoiseEnvelope() {}

    public static final int SERVICE_DAEMON = 0;
    public static final int SERVICE_SENTINEL = 1;
    public static final int SERVICE_VAULT = 2;
    public static final int SERVICE_AUTHD = 3;

    public static final int RESET_UNSPECIFIED = 0;
    public static final int RESET_CANCELLED = 1;
    public static final int RESET_TIMEOUT = 2;
    public static final int RESET_PROTOCOL_ERROR = 3;
    public static final int RESET_REFUSED_STREAM = 4;
    public static final int RESET_INTERNAL_ERROR = 5;
    public static final int RESET_SERVICE_UNAVAILABLE = 6;

    public static final class Header {
        public final String key;
        public final String value;
        public Header(String key, String value) {
            this.key = key != null ? key : "";
            this.value = value != null ? value : "";
        }
    }

    public static final class ApplicationRequest {
        public final String verb;
        public final String path;
        public final List<Header> headers;
        public final byte[] body;
        public final boolean endBody;
        public ApplicationRequest(String verb, String path, List<Header> headers,
                byte[] body, boolean endBody) {
            this.verb = verb != null ? verb : "";
            this.path = path != null ? path : "";
            this.headers = headers != null ? headers : new ArrayList<Header>();
            this.body = body != null ? body : new byte[0];
            this.endBody = endBody;
        }
    }

    public static final class ApplicationResponse {
        public final int status;
        public final List<Header> headers;
        public final byte[] body;
        public final boolean endBody;
        public ApplicationResponse(int status, List<Header> headers, byte[] body, boolean endBody) {
            this.status = status;
            this.headers = headers != null ? headers : new ArrayList<Header>();
            this.body = body != null ? body : new byte[0];
            this.endBody = endBody;
        }
    }

    public static final class BodyChunk {
        public final byte[] data;
        public final boolean endBody;
        public BodyChunk(byte[] data, boolean endBody) {
            this.data = data != null ? data : new byte[0];
            this.endBody = endBody;
        }
    }

    public static final class Reset {
        public final int code;
        public final String reason;
        public Reset(int code, String reason) {
            this.code = code;
            this.reason = reason != null ? reason : "";
        }
    }

    /** A ServiceFrame: exactly one of request/response/bodyChunk/reset when kind != null. */
    public static final class ServiceFrame {
        public final long streamId;
        public final String kind; // "request" | "response" | "body_chunk" | "reset" | null
        public final Object value;

        private ServiceFrame(long streamId, String kind, Object value) {
            this.streamId = streamId;
            this.kind = kind;
            this.value = value;
        }

        public static ServiceFrame request(long streamId, ApplicationRequest request) {
            return new ServiceFrame(streamId, "request", request);
        }
        public static ServiceFrame response(long streamId, ApplicationResponse response) {
            return new ServiceFrame(streamId, "response", response);
        }
        public static ServiceFrame bodyChunk(long streamId, BodyChunk chunk) {
            return new ServiceFrame(streamId, "body_chunk", chunk);
        }
        public static ServiceFrame reset(long streamId, Reset reset) {
            return new ServiceFrame(streamId, "reset", reset);
        }
    }

    public static final class ServiceRequest {
        public final int service;
        public final byte[] payload;
        public ServiceRequest(int service, byte[] payload) {
            this.service = service;
            this.payload = payload != null ? payload : new byte[0];
        }
    }

    public static final class ServiceResponse {
        public final byte[] payload;
        public ServiceResponse(byte[] payload) {
            this.payload = payload != null ? payload : new byte[0];
        }
    }

    // -- Header ----------------------------------------------------------------

    public static byte[] encodeHeader(Header header) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!header.key.isEmpty()) put(out, Proto.stringField(1, header.key));
        if (!header.value.isEmpty()) put(out, Proto.stringField(2, header.value));
        return out.toByteArray();
    }

    public static Header decodeHeader(byte[] data) {
        String key = "", value = "";
        Proto.Reader reader = new Proto.Reader(data);
        while (!reader.atEnd()) {
            int[] k = reader.readKey();
            if (k[0] == 1) {
                requireDelimited(k[1], "Header.key");
                key = decodeString(reader.readDelimited());
            } else if (k[0] == 2) {
                requireDelimited(k[1], "Header.value");
                value = decodeString(reader.readDelimited());
            } else {
                reader.skipField(k[1]);
            }
        }
        return new Header(key, value);
    }

    // -- ApplicationRequest ------------------------------------------------------

    public static byte[] encodeApplicationRequest(ApplicationRequest request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!request.verb.isEmpty()) put(out, Proto.stringField(1, request.verb));
        if (!request.path.isEmpty()) put(out, Proto.stringField(2, request.path));
        for (Header header : request.headers) {
            put(out, Proto.delimitedField(3, encodeHeader(header)));
        }
        if (request.body.length > 0) put(out, Proto.bytesField(4, request.body));
        if (request.endBody) put(out, Proto.boolField(5, true));
        return out.toByteArray();
    }

    public static ApplicationRequest decodeApplicationRequest(byte[] data) {
        String verb = "", path = "";
        List<Header> headers = new ArrayList<>();
        byte[] body = new byte[0];
        boolean endBody = false;
        Proto.Reader reader = new Proto.Reader(data);
        while (!reader.atEnd()) {
            int[] k = reader.readKey();
            switch (k[0]) {
                case 1: requireDelimited(k[1], "verb"); verb = decodeString(reader.readDelimited()); break;
                case 2: requireDelimited(k[1], "path"); path = decodeString(reader.readDelimited()); break;
                case 3: requireDelimited(k[1], "headers"); headers.add(decodeHeader(reader.readDelimited())); break;
                case 4: requireDelimited(k[1], "body"); body = reader.readDelimited(); break;
                case 5: requireVarint(k[1], "end_body"); endBody = reader.readVarint() != 0; break;
                default: reader.skipField(k[1]);
            }
        }
        return new ApplicationRequest(verb, path, headers, body, endBody);
    }

    // -- ApplicationResponse ------------------------------------------------------

    public static byte[] encodeApplicationResponse(ApplicationResponse response) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (response.status != 0) put(out, Proto.int32Field(1, response.status));
        for (Header header : response.headers) {
            put(out, Proto.delimitedField(2, encodeHeader(header)));
        }
        if (response.body.length > 0) put(out, Proto.bytesField(3, response.body));
        if (response.endBody) put(out, Proto.boolField(4, true));
        return out.toByteArray();
    }

    public static ApplicationResponse decodeApplicationResponse(byte[] data) {
        int status = 0;
        List<Header> headers = new ArrayList<>();
        byte[] body = new byte[0];
        boolean endBody = false;
        Proto.Reader reader = new Proto.Reader(data);
        while (!reader.atEnd()) {
            int[] k = reader.readKey();
            switch (k[0]) {
                case 1: requireVarint(k[1], "status"); status = reader.readInt32(); break;
                case 2: requireDelimited(k[1], "headers"); headers.add(decodeHeader(reader.readDelimited())); break;
                case 3: requireDelimited(k[1], "body"); body = reader.readDelimited(); break;
                case 4: requireVarint(k[1], "end_body"); endBody = reader.readVarint() != 0; break;
                default: reader.skipField(k[1]);
            }
        }
        return new ApplicationResponse(status, headers, body, endBody);
    }

    // -- BodyChunk -----------------------------------------------------------------

    public static byte[] encodeBodyChunk(BodyChunk chunk) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (chunk.data.length > 0) put(out, Proto.bytesField(1, chunk.data));
        if (chunk.endBody) put(out, Proto.boolField(2, true));
        return out.toByteArray();
    }

    public static BodyChunk decodeBodyChunk(byte[] data) {
        byte[] chunkData = new byte[0];
        boolean endBody = false;
        Proto.Reader reader = new Proto.Reader(data);
        while (!reader.atEnd()) {
            int[] k = reader.readKey();
            switch (k[0]) {
                case 1: requireDelimited(k[1], "data"); chunkData = reader.readDelimited(); break;
                case 2: requireVarint(k[1], "end_body"); endBody = reader.readVarint() != 0; break;
                default: reader.skipField(k[1]);
            }
        }
        return new BodyChunk(chunkData, endBody);
    }

    // -- Reset ------------------------------------------------------------------------

    public static byte[] encodeReset(Reset reset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (reset.code != RESET_UNSPECIFIED) put(out, Proto.int32Field(1, reset.code));
        if (!reset.reason.isEmpty()) put(out, Proto.stringField(2, reset.reason));
        return out.toByteArray();
    }

    public static Reset decodeReset(byte[] data) {
        int code = RESET_UNSPECIFIED;
        String reason = "";
        Proto.Reader reader = new Proto.Reader(data);
        while (!reader.atEnd()) {
            int[] k = reader.readKey();
            switch (k[0]) {
                case 1: requireVarint(k[1], "code"); code = coerceResetCode(reader.readInt32()); break;
                case 2: requireDelimited(k[1], "reason"); reason = decodeString(reader.readDelimited()); break;
                default: reader.skipField(k[1]);
            }
        }
        return new Reset(code, reason);
    }

    private static int coerceResetCode(int value) {
        if (value < 0 || value > 6) throw new Proto.ProtoError("unknown reset code");
        return value;
    }

    private static int coerceServiceType(long value) {
        if (value < 0 || value > 3) throw new Proto.ProtoError("unknown service type");
        return (int) value;
    }

    // -- ServiceFrame -------------------------------------------------------------------

    public static byte[] encodeServiceFrame(ServiceFrame frame) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (frame.streamId != 0) put(out, Proto.int64Field(1, frame.streamId));
        if (frame.kind == null) {
            if (frame.value != null) throw new Proto.ProtoError("ServiceFrame value provided without kind");
            return out.toByteArray();
        }
        switch (frame.kind) {
            case "request":
                put(out, Proto.delimitedField(2, encodeApplicationRequest((ApplicationRequest) frame.value)));
                break;
            case "response":
                put(out, Proto.delimitedField(3, encodeApplicationResponse((ApplicationResponse) frame.value)));
                break;
            case "body_chunk":
                put(out, Proto.delimitedField(4, encodeBodyChunk((BodyChunk) frame.value)));
                break;
            case "reset":
                put(out, Proto.delimitedField(5, encodeReset((Reset) frame.value)));
                break;
            default:
                throw new Proto.ProtoError("unknown ServiceFrame kind");
        }
        return out.toByteArray();
    }

    public static ServiceFrame decodeServiceFrame(byte[] data) {
        long streamId = 0;
        String kind = null;
        Object value = null;
        Proto.Reader reader = new Proto.Reader(data);
        while (!reader.atEnd()) {
            int[] k = reader.readKey();
            switch (k[0]) {
                case 1: requireVarint(k[1], "stream_id"); streamId = reader.readInt64(); break;
                case 2: requireDelimited(k[1], "request"); kind = "request"; value = decodeApplicationRequest(reader.readDelimited()); break;
                case 3: requireDelimited(k[1], "response"); kind = "response"; value = decodeApplicationResponse(reader.readDelimited()); break;
                case 4: requireDelimited(k[1], "body_chunk"); kind = "body_chunk"; value = decodeBodyChunk(reader.readDelimited()); break;
                case 5: requireDelimited(k[1], "reset"); kind = "reset"; value = decodeReset(reader.readDelimited()); break;
                default: reader.skipField(k[1]);
            }
        }
        return new ServiceFrame(streamId, kind, value);
    }

    // -- ServiceRequest / ServiceResponse -------------------------------------------------

    public static byte[] encodeServiceRequest(ServiceRequest request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (request.service != SERVICE_DAEMON) put(out, Proto.varintField(1, request.service));
        if (request.payload.length > 0) put(out, Proto.bytesField(2, request.payload));
        return out.toByteArray();
    }

    public static ServiceRequest decodeServiceRequest(byte[] data) {
        int service = SERVICE_DAEMON;
        byte[] payload = new byte[0];
        Proto.Reader reader = new Proto.Reader(data);
        while (!reader.atEnd()) {
            int[] k = reader.readKey();
            switch (k[0]) {
                case 1: requireVarint(k[1], "service"); service = coerceServiceType(reader.readVarint()); break;
                case 2: requireDelimited(k[1], "payload"); payload = reader.readDelimited(); break;
                default: reader.skipField(k[1]);
            }
        }
        return new ServiceRequest(service, payload);
    }

    public static byte[] encodeServiceResponse(ServiceResponse response) {
        if (response.payload.length == 0) return new byte[0];
        return Proto.bytesField(1, response.payload);
    }

    public static ServiceResponse decodeServiceResponse(byte[] data) {
        byte[] payload = new byte[0];
        Proto.Reader reader = new Proto.Reader(data);
        while (!reader.atEnd()) {
            int[] k = reader.readKey();
            if (k[0] == 1) {
                requireDelimited(k[1], "payload");
                payload = reader.readDelimited();
            } else {
                reader.skipField(k[1]);
            }
        }
        return new ServiceResponse(payload);
    }

    // -- helpers -----------------------------------------------------------------------------

    private static void put(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static void requireDelimited(int wire, String what) {
        if (wire != Proto.WIRE_DELIMITED) throw new Proto.ProtoError(what + " wrong wire type");
    }

    private static void requireVarint(int wire, String what) {
        if (wire != Proto.WIRE_VARINT) throw new Proto.ProtoError(what + " wrong wire type");
    }

    private static String decodeString(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }
}
