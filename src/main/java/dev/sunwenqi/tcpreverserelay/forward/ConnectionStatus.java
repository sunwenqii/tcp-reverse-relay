package dev.sunwenqi.tcpreverserelay.forward;

import dev.sunwenqi.tcpreverserelay.config.ServerConnection;

/** A consistent snapshot: saving an endpoint does not imply that any mapping was registered. */
public record ConnectionStatus(ServerConnection server, String mode, int enabledMappings, int registeredMappings, String lastError) {
    public int pendingMappings() { return enabledMappings - registeredMappings; }
}
