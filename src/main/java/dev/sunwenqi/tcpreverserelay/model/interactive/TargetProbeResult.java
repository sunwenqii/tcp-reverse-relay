package dev.sunwenqi.tcpreverserelay.model.interactive;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TargetProbeResult {
    private boolean reachable;
    private String host;
    private int port;
    private String message;
    private long durationMs;
}
