package dev.sunwenqi.tcpreverserelay.forward;

import java.util.List;

public record DiagnosticReport(int port, long checkedAt, List<Check> checks, String note) {
    public record Check(String name, String status, String detail, long durationMs) {}
    public static final String NOTE = "仅检查 TCP 连通性，不验证应用协议；公网防火墙需要从外部设备确认。";
}
