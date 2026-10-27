package dev.sunwenqi.tcpreverserelay.serverside;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import dev.sunwenqi.tcpreverserelay.config.*;

/** Serializes admission and mapping commits. Disk persistence succeeds before publishing ownership. */
public final class PortOwnershipRegistry {
    private final Path file;
    private final Map<Integer, String> owners = new LinkedHashMap<>();
    private Set<Integer> enabled;
    public PortOwnershipRegistry(Path file, Collection<Integer> ports, Set<Integer> disabled, Map<Integer, String> owners) {
        this.file = file; this.owners.putAll(owners);
        this.enabled = new HashSet<>(ports); this.enabled.removeAll(disabled);
    }
    public synchronized String owner(int port) { return owners.getOrDefault(port, ""); }
    public synchronized void claim(int port, String clientId) throws IOException {
        PortOwners.identity(clientId);
        if (!enabled.contains(port)) throw new IllegalArgumentException("此端口已暂停或尚未应用");
        String previous = owners.get(port);
        if (previous != null && !previous.equals(clientId))
            throw new IllegalArgumentException("端口 " + port + " 已归属客户端 " + previous + "，请联系管理员调整归属");
        if (previous == null) assign(port, clientId);
    }
    public synchronized void assign(int port, String clientId) throws IOException {
        var next = new LinkedHashMap<>(owners);
        if (clientId.isEmpty()) next.remove(port); else next.put(port, PortOwners.identity(clientId));
        ConfigFile.update(file, Map.of("port.owners", PortOwners.format(next)));
        owners.clear(); owners.putAll(next);
    }
    public synchronized Map<Integer, String> nextOwners(Set<Integer> ports, Integer oldPort, Integer newPort) {
        var next = new LinkedHashMap<>(owners);
        if (oldPort != null && !oldPort.equals(newPort) && next.containsKey(oldPort)) next.put(newPort, next.remove(oldPort));
        next.keySet().retainAll(ports);
        return next;
    }
    public synchronized void publish(Set<Integer> ports, Set<Integer> disabled, Map<Integer, String> next) {
        owners.clear(); owners.putAll(next);
        enabled = new HashSet<>(ports); enabled.removeAll(disabled);
    }
}
