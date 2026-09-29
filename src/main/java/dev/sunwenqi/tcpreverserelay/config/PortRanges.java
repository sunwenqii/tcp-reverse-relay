package dev.sunwenqi.tcpreverserelay.config;

import java.util.*;

/**
 * Server port lists. A-B includes A and excludes B.
 */
public final class PortRanges {
    public static final int MAX_PORTS = 4096;

    private PortRanges() {
    }

    public static List<Integer> parse(String value, int handshakePort, int adminPort) {
        List<Integer> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        if (value.isBlank()) return List.of();

        for (String raw : value.split(",", -1)) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                throw new IllegalArgumentException("forwards 中存在空端口，请检查多余的逗号");
            }
            String[] bounds = entry.split("-", -1);
            if (bounds.length == 1) {
                add(AppSettings.parsePort(bounds[0], "forwards"), handshakePort, adminPort, seen, result);
            } else if (bounds.length == 2) {
                int start = AppSettings.parsePort(bounds[0], "forwards 起始端口");
                int end = AppSettings.parsePort(bounds[1], "forwards 结束端口");
                if (start >= end) {
                    throw new IllegalArgumentException("forwards 区间必须满足起始端口 < 结束端口: " + entry);
                }
                for (int port = start; port < end; port++) {
                    add(port, handshakePort, adminPort, seen, result);
                }
            } else {
                throw new IllegalArgumentException("forwards 端口区间格式应为 起始端口-结束端口: " + entry);
            }
        }
        return List.copyOf(result);
    }

    private static void add(int port, int handshakePort, int adminPort,
                            Set<Integer> seen, List<Integer> result) {
        if (port == handshakePort || port == adminPort || !seen.add(port)) {
            throw new IllegalArgumentException("forwards 中的端口重复或与握手/管理端口冲突: " + port);
        }
        if (result.size() == MAX_PORTS) {
            throw new IllegalArgumentException("forwards 最多可配置 " + MAX_PORTS + " 个端口");
        }
        result.add(port);
    }

    public static List<String> compact(Collection<Integer> ports) {
        List<String> result = new ArrayList<>();
        TreeSet<Integer> sorted = new TreeSet<>(ports);
        Integer start = null;
        int end = 0;
        for (int port : sorted) {
            if (start == null) {
                start = port;
                end = port;
            } else if (port == end + 1) {
                end = port;
            } else {
                append(result, start, end);
                start = port;
                end = port;
            }
        }
        if (start != null) append(result, start, end);
        return result;
    }

    public static void requireWithinLimit(Collection<Integer> ports) {
        if (ports.size() > MAX_PORTS) {
            throw new IllegalArgumentException("forwards 最多可配置 " + MAX_PORTS + " 个端口");
        }
    }

    private static void append(List<String> result, int start, int end) {
        if (end == 65535 && start < end) {
            append(result, start, end - 1);
            result.add("65535");
        } else if (start == end) {
            result.add(Integer.toString(start));
        } else {
            result.add(start + "-" + (end + 1));
        }
    }
}
