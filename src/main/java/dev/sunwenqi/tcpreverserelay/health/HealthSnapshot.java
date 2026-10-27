package dev.sunwenqi.tcpreverserelay.health;

/** TCP reachability, deliberately separate from registration and application-level health. */
public record HealthSnapshot(String state, String host, int port, long checkedAt, long durationMs,
                             int failures, int intervalSeconds, String message) {
    public static HealthSnapshot waiting(String state) { return new HealthSnapshot(state, "", 0, 0, 0, 0, 15, ""); }
    public HealthSnapshot staleAt(long receivedAt, long now) {
        return now - receivedAt > Math.max(30_000L, intervalSeconds * 3000L)
                ? new HealthSnapshot("stale", host, port, checkedAt, durationMs, failures, intervalSeconds, "检查结果已过期") : this;
    }
}
