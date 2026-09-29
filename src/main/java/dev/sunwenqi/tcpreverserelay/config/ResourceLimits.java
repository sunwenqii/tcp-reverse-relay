package dev.sunwenqi.tcpreverserelay.config;

public final class ResourceLimits {
    private ResourceLimits() {
    }

    public static int fromEnv(String name, int defaultValue, int maxValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0 && parsed <= maxValue) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 在下面统一给出明确配置错误。
        }
        throw new IllegalArgumentException(name + " 必须是 1 到 " + maxValue + " 之间的整数");
    }
}
