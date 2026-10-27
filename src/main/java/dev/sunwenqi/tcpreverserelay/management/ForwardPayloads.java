package dev.sunwenqi.tcpreverserelay.management;

import java.util.*;

import dev.sunwenqi.tcpreverserelay.forward.ConnectionStatus;
import dev.sunwenqi.tcpreverserelay.forward.ForwardStatus;
import dev.sunwenqi.tcpreverserelay.health.HealthSettings;

/** Explicit management API schema, independent of record/bean serializer conventions. */
final class ForwardPayloads {
    private ForwardPayloads() {}
    static Map<String, Object> status(ForwardStatus item) {
        var result = new LinkedHashMap<String, Object>();
        result.put("port", item.port()); result.put("target", item.target()); result.put("targetHost", item.targetHost());
        result.put("connected", item.connected()); result.put("connections", item.connections()); result.put("enabled", item.enabled());
        result.put("state", item.state()); result.put("lastError", item.lastError()); result.put("lastAttemptAt", item.lastAttemptAt());
        result.put("nextRetryAt", item.nextRetryAt()); result.put("clientId", item.clientId()); result.put("clientName", item.clientName());
        result.put("clientAddress", item.clientAddress()); result.put("connectedAt", item.connectedAt()); result.put("lastSeenAt", item.lastSeenAt());
        result.put("ownerId", item.ownerId());
        var health = item.health();
        result.put("health", Map.of("state", health.state(), "host", health.host(), "port", health.port(), "checkedAt", health.checkedAt(),
                "durationMs", health.durationMs(), "failures", health.failures(), "intervalSeconds", health.intervalSeconds(), "message", health.message()));
        return result;
    }
    static Map<String, Object> connection(ConnectionStatus status) {
        return Map.of("host", status.server().host(), "port", status.server().port(), "mode", status.mode(),
                "enabledMappings", status.enabledMappings(), "registeredMappings", status.registeredMappings(),
                "pendingMappings", status.pendingMappings(), "lastError", status.lastError());
    }
    static Map<String, Object> healthSettings(HealthSettings settings) {
        return Map.of("enabled", settings.enabled(), "intervalSeconds", settings.intervalSeconds(),
                "timeoutMillis", settings.timeoutMillis(), "failureThreshold", settings.failureThreshold());
    }
}
