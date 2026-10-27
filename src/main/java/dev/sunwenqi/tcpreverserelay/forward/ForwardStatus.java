package dev.sunwenqi.tcpreverserelay.forward;

import dev.sunwenqi.tcpreverserelay.health.HealthSnapshot;

/**
 * A snapshot for management and monitoring; target is absent on the server.
 */
public record ForwardStatus(int port, Integer target, boolean connected, int connections, String targetHost,
                            boolean enabled, String state, String lastError, long lastAttemptAt, long nextRetryAt,
                            String clientId, String clientName, String clientAddress, long connectedAt, long lastSeenAt, String ownerId, HealthSnapshot health) {
    public ForwardStatus(int port, Integer target, boolean connected, int connections, String targetHost, boolean enabled, String state,
                         String lastError, long lastAttemptAt, long nextRetryAt, String clientId, String clientName, String clientAddress, long connectedAt, long lastSeenAt) {
        this(port, target, connected, connections, targetHost, enabled, state, lastError, lastAttemptAt, nextRetryAt, clientId, clientName, clientAddress,
                connectedAt, lastSeenAt, "", HealthSnapshot.waiting("pending"));
    }
    public ForwardStatus withMonitoring(String owner, HealthSnapshot snapshot) {
        return new ForwardStatus(port, target, connected, connections, targetHost, enabled, state, lastError, lastAttemptAt, nextRetryAt,
                clientId, clientName, clientAddress, connectedAt, lastSeenAt, owner, snapshot);
    }
    public ForwardStatus(int port, Integer target, boolean connected, int connections) {
        this(port, target, connected, connections, target == null ? null : "127.0.0.1", true,
                connected ? "online" : "offline", "", 0, 0, "", "", "", 0, 0);
    }
}
