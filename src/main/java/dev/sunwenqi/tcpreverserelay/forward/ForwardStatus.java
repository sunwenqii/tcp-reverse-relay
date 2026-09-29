package dev.sunwenqi.tcpreverserelay.forward;

/**
 * A snapshot for management and monitoring; target is absent on the server.
 */
public record ForwardStatus(int port, Integer target, boolean connected, int connections) {
}
