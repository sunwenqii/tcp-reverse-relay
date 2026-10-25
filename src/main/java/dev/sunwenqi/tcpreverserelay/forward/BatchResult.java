package dev.sunwenqi.tcpreverserelay.forward;

import java.util.List;

public record BatchResult(String action, boolean preview, List<Integer> ports, int connections, String message) {}
