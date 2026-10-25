package dev.sunwenqi.tcpreverserelay.forward;

import java.util.List;

public record ClientInfo(String id, String name, String address, String version, boolean online,
                         long connectedAt, long lastSeenAt, List<ForwardStatus> mappings) {}
