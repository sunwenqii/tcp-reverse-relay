package dev.sunwenqi.tcpreverserelay.config;

import java.util.*;
import java.util.stream.Collectors;

/** Stable client identities bound to public ports; no source-address guessing for modern clients. */
public final class PortOwners {
    private PortOwners() {}
    public static String identity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("客户端标识需为 1–64 位字母、数字、点、下划线或连字符");
        return value;
    }
    public static Map<Integer, String> parse(String value, Collection<Integer> ports) {
        Map<Integer, String> owners = new LinkedHashMap<>();
        if (value.isBlank()) return Map.of();
        for (String entry : value.split(",", -1)) {
            String[] parts = entry.trim().split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("port.owners 格式为 公网端口:客户端标识，用逗号分隔");
            int port = AppSettings.parsePort(parts[0], "port.owners 端口");
            if (!ports.contains(port)) throw new IllegalArgumentException("port.owners 包含未配置端口: " + port);
            if (owners.putIfAbsent(port, identity(parts[1])) != null)
                throw new IllegalArgumentException("port.owners 端口重复: " + port);
        }
        return Map.copyOf(owners);
    }
    public static String format(Map<Integer, String> owners) {
        return owners.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ":" + identity(e.getValue())).collect(Collectors.joining(","));
    }
}
