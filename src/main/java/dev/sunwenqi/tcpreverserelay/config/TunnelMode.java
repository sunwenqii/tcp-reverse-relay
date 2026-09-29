package dev.sunwenqi.tcpreverserelay.config;

import java.util.Locale;

/**
 * <p>
 * 隧道运行模式枚举
 * </p>
 *
 * <p>
 * 定义当前 TCP Reverse Relay 系统支持的通信模式。
 * 不同模式对应不同的安全策略与监听实现方式。
 * </p>
 *
 * @author Sunwenqi
 * @since 2026-01-08
 */
public enum TunnelMode {

    /**
     * 普通明文模式
     */
    SIMPLE,

    /**
     * 握手加密模式
     */
    SECRET,

    /**
     * 全链路加密模式
     */
    SECRET_ALL;

    /**
     * 字符串解析为枚举
     *
     * @param input 用户输入
     * @return 解析后的模式，默认 SECRET
     */
    public static TunnelMode parse(String input) {
        if (input == null || input.isBlank()) {
            return SECRET;
        }
        String normalized = input.trim().replace("_", "").replace("-", "").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SIMPLE" -> SIMPLE;
            case "SECRET" -> SECRET;
            case "SECRETALL" -> SECRET_ALL;
            default -> throw new IllegalArgumentException("不支持的运行模式: " + input);
        };
    }
}
