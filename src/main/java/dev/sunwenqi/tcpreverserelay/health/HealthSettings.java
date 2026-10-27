package dev.sunwenqi.tcpreverserelay.health;

import java.util.*;

public record HealthSettings(boolean enabled, int intervalSeconds, int timeoutMillis, int failureThreshold) {
    public static final HealthSettings DEFAULT = new HealthSettings(true, 15, 2000, 2);
    public HealthSettings {
        range(intervalSeconds, 3, 3600, "health.interval.seconds");
        range(timeoutMillis, 200, 10000, "health.timeout.millis");
        range(failureThreshold, 1, 10, "health.failure.threshold");
    }
    public static HealthSettings from(Properties p) {
        String enabled = p.getProperty("health.enabled", "true").trim();
        if (!enabled.equals("true") && !enabled.equals("false")) throw new IllegalArgumentException("health.enabled 必须为 true 或 false");
        try {
            return new HealthSettings(Boolean.parseBoolean(enabled),
                    Integer.parseInt(p.getProperty("health.interval.seconds", "15").trim()),
                    Integer.parseInt(p.getProperty("health.timeout.millis", "2000").trim()),
                    Integer.parseInt(p.getProperty("health.failure.threshold", "2").trim()));
        } catch (NumberFormatException e) { throw new IllegalArgumentException("health 检查间隔、超时和失败次数必须是整数"); }
    }
    public Map<String, String> properties() {
        return Map.of("health.enabled", String.valueOf(enabled), "health.interval.seconds", String.valueOf(intervalSeconds),
                "health.timeout.millis", String.valueOf(timeoutMillis), "health.failure.threshold", String.valueOf(failureThreshold));
    }
    private static void range(int n, int min, int max, String key) {
        if (n < min || n > max) throw new IllegalArgumentException(key + " 必须为 " + min + "–" + max);
    }
}
