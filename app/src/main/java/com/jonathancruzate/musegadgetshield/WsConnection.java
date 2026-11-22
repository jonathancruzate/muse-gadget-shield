package com.jonathancruzate.musegadgetshield;

import java.io.IOException;

/**
 * One open WebSocket connection, as the link session uses it. The
 * production implementation is {@link WsClient}; tests substitute a
 * bridged connection (the SDK's LinkSession likewise takes a connect
 * callable so its tests can run against an in-memory VM).
 */
public interface WsConnection {
    void send(byte[] payload) throws IOException;
    void close();
}
