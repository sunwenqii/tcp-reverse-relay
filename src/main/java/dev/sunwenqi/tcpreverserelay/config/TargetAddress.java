package dev.sunwenqi.tcpreverserelay.config;

import java.net.IDN;
import java.net.URI;

public final class TargetAddress {
    private TargetAddress() {}

    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("目标地址不能为空");
        String host = raw.trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.length() > 253 || host.chars().anyMatch(Character::isWhitespace)
                || host.matches(".*[/\\\\@?#,=].*"))
            throw new IllegalArgumentException("目标地址应为 IP 或主机名，不要填写协议、路径或端口");
        try {
            if (host.contains(":")) {
                new URI("tcp://[" + host + "]:1");
            } else {
                host = IDN.toASCII(host);
                if (!host.matches("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"))
                    throw new IllegalArgumentException();
                if (host.matches("[0-9.]+")) {
                    String[] parts = host.split("\\.", -1);
                    if (parts.length != 4) throw new IllegalArgumentException();
                    for (String part : parts) if (Integer.parseInt(part) > 255 || part.isEmpty())
                        throw new IllegalArgumentException();
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的目标地址: " + raw);
        }
        return host;
    }

    public static String display(String host, int port) {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }
}
