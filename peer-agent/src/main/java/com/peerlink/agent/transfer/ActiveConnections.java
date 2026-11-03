package com.peerlink.agent.transfer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Open outgoing transfer connections, so shutdown can close them and unblock their threads. */
final class ActiveConnections {

    private final Set<AutoCloseable> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    void register(AutoCloseable connection) {
        open.add(connection);
        if (closed) {
            closeQuietly(connection);
        }
    }

    void unregister(AutoCloseable connection) {
        open.remove(connection);
    }

    boolean closed() {
        return closed;
    }

    void closeAll() {
        closed = true;
        open.forEach(ActiveConnections::closeQuietly);
    }

    private static void closeQuietly(AutoCloseable connection) {
        try {
            connection.close();
        } catch (Exception e) {
            // Shutting down.
        }
    }
}
