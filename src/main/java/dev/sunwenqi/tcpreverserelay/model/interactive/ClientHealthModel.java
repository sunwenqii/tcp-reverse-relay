package dev.sunwenqi.tcpreverserelay.model.interactive;

import dev.sunwenqi.tcpreverserelay.health.HealthSnapshot;

import lombok.Data;

/** Mutable wire DTO; immutable snapshots remain internal. */
@Data
public class ClientHealthModel {
    private String state, host, message;
    private int port, failures, intervalSeconds;
    private long checkedAt, durationMs;
    public static ClientHealthModel from(HealthSnapshot snapshot) {
        var result = new ClientHealthModel();
        result.state = snapshot.state(); result.host = snapshot.host(); result.port = snapshot.port();
        result.checkedAt = snapshot.checkedAt(); result.durationMs = snapshot.durationMs();
        result.failures = snapshot.failures(); result.intervalSeconds = snapshot.intervalSeconds(); result.message = snapshot.message();
        return result;
    }
    public HealthSnapshot snapshot() {
        if (!java.util.Set.of("pending", "up", "suspect", "down", "disabled").contains(state == null ? "" : state)
                || intervalSeconds < 3 || intervalSeconds > 3600 || failures < 0 || failures > 1_000_000
                || durationMs < 0 || durationMs > 60_000 || checkedAt < 0 || message != null && message.length() > 512)
            throw new IllegalArgumentException("无效的目标连通性报告");
        return new HealthSnapshot(state, host, port, checkedAt, durationMs, failures, intervalSeconds, message == null ? "" : message);
    }
}
