package dev.sunwenqi.tcpreverserelay.config;

import java.util.Base64;

/**
 * Validates the tunnel keys loaded from the local configuration file.
 */
public final class SecurityConfig {
    private SecurityConfig() {
    }

    public record Keys(String aesKey, String tokenKey) {
        @Override
        public String toString() {
            return "Keys[redacted]";
        }
    }

    public static void validate(TunnelMode mode, Keys keys) {
        if (mode == TunnelMode.SIMPLE) {
            return;
        }
        if (keys.aesKey() == null || keys.aesKey().isBlank()) {
            throw new IllegalStateException("请在 config/server.properties 和 config/client.properties 中填写相同的 AES_KEY");
        }
        if (keys.tokenKey() == null || keys.tokenKey().length() < 32) {
            throw new IllegalStateException("请在 config/server.properties 和 config/client.properties 中填写相同的 TOKEN_KEY，至少 32 个字符");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(keys.aesKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("配置文件中的 AES_KEY 必须是 Base64 编码的 AES 密钥", e);
        }
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalStateException("配置文件中的 AES_KEY 解码后必须为 16、24 或 32 字节");
        }
    }
}
