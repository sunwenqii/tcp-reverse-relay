package dev.sunwenqi.tcpreverserelay.clientside;

import dev.sunwenqi.tcpreverserelay.clientside.config.*;
import dev.sunwenqi.tcpreverserelay.config.*;
import dev.sunwenqi.tcpreverserelay.forward.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Owns saved mappings; registration runs asynchronously and is reported separately. */
public final class ClientRuntime implements ForwardManager {
    private final Path file;
    private final AppSettings.Client settings;
    private final LinkedHashMap<Integer, AppSettings.Mapping> mappings = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, ClientControlThread> controls = new LinkedHashMap<>();
    private final LinkedHashSet<Integer> disabled = new LinkedHashSet<>();

    public ClientRuntime(Path file, AppSettings.Client settings) { this.file = file; this.settings = settings; }

    public synchronized void start() {
        disabled.addAll(settings.disabledForwards());
        for (var mapping : settings.forwards()) {
            mappings.put(mapping.remotePort(), mapping);
            if (!disabled.contains(mapping.remotePort())) {
                ClientControlThread control = create(mapping);
                controls.put(mapping.remotePort(), control);
                control.startWithReconnect();
            }
        }
    }

    @Override public synchronized void upsert(Integer oldPort, int remotePort, Integer localPort) throws IOException {
        String host = oldPort != null && mappings.containsKey(oldPort) ? mappings.get(oldPort).targetHost() : "127.0.0.1";
        upsert(oldPort, remotePort, host, localPort);
    }

    @Override public synchronized void upsert(Integer oldPort, int remotePort, String host, Integer localPort) throws IOException {
        if (localPort == null) throw new IllegalArgumentException("客户端需要填写目标端口");
        var parsed = AppSettings.parseMappings(remotePort + ":" + TargetAddress.display(TargetAddress.validate(host), localPort), settings.adminPort());
        if (oldPort != null && !mappings.containsKey(oldPort)) throw new IllegalArgumentException("原公网端口不存在: " + oldPort);
        if (mappings.containsKey(remotePort) && !Objects.equals(oldPort, remotePort)) throw new IllegalArgumentException("公网端口已配置: " + remotePort);
        var next = new LinkedHashMap<>(mappings);
        var paused = new LinkedHashSet<>(disabled);
        boolean keepPaused = oldPort != null && paused.remove(oldPort);
        if (oldPort != null) next.remove(oldPort);
        next.put(remotePort, parsed.get(0));
        if (keepPaused) paused.add(remotePort);
        apply(next, paused);
    }

    @Override public synchronized void delete(int port) throws IOException { batch("delete", Integer.toString(port), false); }

    @Override public synchronized BatchResult batch(String action, String entries, boolean preview) throws IOException {
        if (!Set.of("add", "delete", "enable", "disable").contains(action)) throw new IllegalArgumentException("不支持的批量操作");
        var next = new LinkedHashMap<>(mappings);
        var paused = new LinkedHashSet<>(disabled);
        List<Integer> affected = new ArrayList<>();
        if (action.equals("add")) {
            for (var mapping : AppSettings.parseMappings(entries, settings.adminPort())) {
                if (next.containsKey(mapping.remotePort())) throw new IllegalArgumentException("公网端口已配置: " + mapping.remotePort());
                next.put(mapping.remotePort(), mapping);
                affected.add(mapping.remotePort());
            }
        } else {
            affected.addAll(PortRanges.parse(entries.trim().replaceAll("\\R+", ","), 0, 0));
            for (int port : affected) {
                if (!next.containsKey(port)) throw new IllegalArgumentException("端口不存在: " + port);
                switch (action) {
                    case "delete" -> { next.remove(port); paused.remove(port); }
                    case "enable" -> paused.remove(port);
                    case "disable" -> paused.add(port);
                }
            }
        }
        if (affected.isEmpty()) throw new IllegalArgumentException("请至少选择或填写一个映射");
        if (next.size() > 4096) throw new IllegalArgumentException("最多可配置 4096 个映射");
        int connections = affected.stream().map(controls::get).filter(Objects::nonNull).mapToInt(ClientControlThread::getConnectionCount).sum();
        if (!preview) apply(next, paused);
        return new BatchResult(action, preview, List.copyOf(affected), connections,
                preview ? "校验通过，应用时会再次校验当前配置" : "配置已保存；启用的映射将自动向服务端注册");
    }

    private void apply(LinkedHashMap<Integer, AppSettings.Mapping> next, LinkedHashSet<Integer> paused) throws IOException {
        if (next.size() > 4096) throw new IllegalArgumentException("最多可配置 4096 个映射");
        var nextControls = new LinkedHashMap<Integer, ClientControlThread>();
        for (var mapping : next.values()) {
            if (!paused.contains(mapping.remotePort())) {
                ClientControlThread old = controls.get(mapping.remotePort());
                nextControls.put(mapping.remotePort(), old != null ? old : create(mapping));
            }
        }
        ForwardFile.save(file, next.values().stream().map(AppSettings.Mapping::configEntry).toList(), paused);
        controls.forEach((port, control) -> { if (nextControls.get(port) != control) control.cancell(); });
        nextControls.forEach((port, control) -> {
            var mapping = next.get(port);
            if (controls.get(port) == control) {
                if (!mapping.equals(mappings.get(port))) control.setDestIpPort(mapping.targetHost(), mapping.localPort());
            } else control.startWithReconnect();
        });
        mappings.clear(); mappings.putAll(next);
        controls.clear(); controls.putAll(nextControls);
        disabled.clear(); disabled.addAll(paused);
    }

    @Override public synchronized List<ForwardStatus> list() {
        List<ForwardStatus> result = new ArrayList<>();
        for (var mapping : mappings.values()) {
            var control = controls.get(mapping.remotePort());
            boolean enabled = !disabled.contains(mapping.remotePort());
            boolean online = control != null && control.isAlive();
            String error = control == null ? "" : control.getLastError();
            String state = !enabled ? "paused" : online ? "online" : control != null && control.isConnecting()
                    ? "connecting" : error.isEmpty() ? "pending" : "error";
            result.add(new ForwardStatus(mapping.remotePort(), mapping.localPort(), online,
                    control == null ? 0 : control.getConnectionCount(), mapping.targetHost(), enabled, state, error,
                    control == null ? 0 : control.getLastAttemptAt(), control == null ? 0 : control.getNextRetryAt(),
                    settings.clientId(), settings.clientName(), "", control == null ? 0 : control.getConnectedAt(),
                    control == null ? 0 : control.getLastSeenAt()));
        }
        return result;
    }

    @Override public DiagnosticReport diagnose(int port) {
        AppSettings.Mapping mapping;
        ForwardStatus state;
        synchronized (this) {
            mapping = mappings.get(port);
            if (mapping == null) throw new IllegalArgumentException("端口不存在: " + port);
            state = list().stream().filter(item -> item.port() == port).findFirst().orElseThrow();
        }
        var checks = new ArrayList<DiagnosticReport.Check>();
        var server = TcpDiagnostics.probe(settings.serverHost(), settings.handshakePort());
        checks.add(new DiagnosticReport.Check("服务端连接", server.isReachable() ? "passed" : "failed",
                TargetAddress.display(settings.serverHost(), settings.handshakePort()) + " · " + server.getMessage(), server.getDurationMs()));
        checks.add(new DiagnosticReport.Check("端口注册", !state.enabled() ? "skipped" : state.connected() ? "passed" : "pending",
                !state.enabled() ? "映射已暂停" : state.connected() ? "服务端已接受此端口注册" : state.lastError().isEmpty() ? "等待注册" : state.lastError(), 0));
        var target = TcpDiagnostics.probe(mapping.targetHost(), mapping.localPort());
        checks.add(new DiagnosticReport.Check("目标服务", target.isReachable() ? "passed" : "failed",
                TargetAddress.display(mapping.targetHost(), mapping.localPort()) + " · " + target.getMessage(), target.getDurationMs()));
        return new DiagnosticReport(port, System.currentTimeMillis(), checks, DiagnosticReport.NOTE);
    }

    public synchronized void close() {
        controls.values().forEach(ClientControlThread::cancell);
        controls.clear(); mappings.clear(); disabled.clear();
    }

    private ClientControlThread create(AppSettings.Mapping mapping) {
        InteractiveClientConfig config;
        if (settings.mode() == TunnelMode.SIMPLE) config = new InteractiveClientConfig();
        else if (settings.mode() == TunnelMode.SECRET) {
            var secret = new SecretInteractiveClientConfig();
            secret.setBaseAesKey(settings.keys().aesKey()); secret.setTokenKey(settings.keys().tokenKey()); config = secret;
        } else {
            var secret = new AllSecretInteractiveClientConfig();
            secret.setBaseAesKey(settings.keys().aesKey()); secret.setTokenKey(settings.keys().tokenKey());
            secret.setBasePasswayKey(settings.keys().aesKey()); config = secret;
        }
        config.setClientServiceIp(settings.serverHost()); config.setClientServicePort(settings.handshakePort());
        config.setClientId(settings.clientId()); config.setClientName(settings.clientName());
        config.setListenServerPort(mapping.remotePort()); config.setDestIpPort(mapping.targetHost(), mapping.localPort());
        config.setTryRecipientCount(0);
        return new ClientControlThread(config);
    }
}
