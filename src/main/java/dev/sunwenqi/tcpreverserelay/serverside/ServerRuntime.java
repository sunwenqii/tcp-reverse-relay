package dev.sunwenqi.tcpreverserelay.serverside;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import dev.sunwenqi.tcpreverserelay.config.*;
import dev.sunwenqi.tcpreverserelay.forward.*;
import dev.sunwenqi.tcpreverserelay.health.HealthSnapshot;
import dev.sunwenqi.tcpreverserelay.serverside.client.ClientServiceThread;
import dev.sunwenqi.tcpreverserelay.serverside.client.config.*;
import dev.sunwenqi.tcpreverserelay.serverside.listen.*;
import dev.sunwenqi.tcpreverserelay.serverside.listen.config.*;

/** Binds candidate ports before committing a single configuration update. */
public final class ServerRuntime implements ForwardManager {
    private final Path file;
    private final AppSettings.Server settings;
    private final LinkedHashSet<Integer> ports = new LinkedHashSet<>();
    private final LinkedHashSet<Integer> disabled = new LinkedHashSet<>();
    private final LinkedHashMap<String, ClientInfo> knownClients = new LinkedHashMap<>();
    private ClientServiceThread service;
    private final PortOwnershipRegistry ownership;

    public ServerRuntime(Path file, AppSettings.Server settings) { this.file = file; this.settings = settings;
        this.ownership = new PortOwnershipRegistry(file, settings.forwards(), settings.disabledForwards(), settings.portOwners()); }

    public synchronized void start() throws Exception {
        try {
            disabled.addAll(settings.disabledForwards());
            for (int port : settings.forwards()) {
                if (!disabled.contains(port)) bind(port);
                ports.add(port);
            }
            SimpleClientServiceConfig config;
            if (settings.mode() == TunnelMode.SIMPLE) config = new SimpleClientServiceConfig(settings.handshakePort());
            else {
                var secret = new SecretSimpleClientServiceConfig(settings.handshakePort());
                secret.setBaseAesKey(settings.keys().aesKey()); secret.setTokenKey(settings.keys().tokenKey()); config = secret;
            }
            service = new ClientServiceThread(config);
            service.start();
        } catch (Exception e) { close(); throw e; }
    }

    public synchronized void upsert(Integer oldPort, int newPort) throws IOException {
        validatePort(newPort);
        if (oldPort != null && !ports.contains(oldPort)) throw new IllegalArgumentException("原端口不存在: " + oldPort);
        if (ports.contains(newPort) && !Objects.equals(oldPort, newPort)) throw new IllegalArgumentException("端口已配置: " + newPort);
        var next = new LinkedHashSet<>(ports);
        var paused = new LinkedHashSet<>(disabled);
        boolean keepPaused = oldPort != null && paused.remove(oldPort);
        if (oldPort != null) next.remove(oldPort);
        next.add(newPort);
        if (keepPaused) paused.add(newPort);
        apply(next, paused, oldPort, newPort);
    }

    @Override public synchronized void upsert(Integer oldPort, int port, Integer target) throws IOException {
        if (target != null) throw new IllegalArgumentException("服务端映射不接受目标端口");
        upsert(oldPort, port);
    }

    @Override public synchronized void upsert(Integer oldPort, int port, String host, Integer target) throws IOException {
        if (host != null && !host.isBlank()) throw new IllegalArgumentException("服务端映射不接受目标地址");
        upsert(oldPort, port, target);
    }

    @Override public synchronized void delete(int port) throws IOException { batch("delete", Integer.toString(port), false); }

    @Override public synchronized BatchResult batch(String action, String entries, boolean preview) throws IOException {
        if (!Set.of("add", "delete", "enable", "disable").contains(action)) throw new IllegalArgumentException("不支持的批量操作");
        List<Integer> affected = PortRanges.parse(entries.trim().replaceAll("\\R+", ","), settings.handshakePort(), settings.adminPort());
        if (affected.isEmpty()) throw new IllegalArgumentException("请至少选择或填写一个端口");
        var next = new LinkedHashSet<>(ports);
        var paused = new LinkedHashSet<>(disabled);
        for (int port : affected) {
            if (action.equals("add")) {
                if (!next.add(port)) throw new IllegalArgumentException("端口已配置: " + port);
            } else {
                if (!next.contains(port)) throw new IllegalArgumentException("端口不存在: " + port);
                switch (action) {
                    case "delete" -> { next.remove(port); paused.remove(port); }
                    case "enable" -> paused.remove(port);
                    case "disable" -> paused.add(port);
                }
            }
        }
        PortRanges.requireWithinLimit(next);
        int connections = list().stream().filter(item -> affected.contains(item.port())).mapToInt(ForwardStatus::connections).sum();
        if (!preview) apply(next, paused);
        return new BatchResult(action, preview, List.copyOf(affected), connections,
                preview ? "配置校验通过，端口能否绑定以应用时结果为准" : "端口配置已保存并应用");
    }

    private void apply(LinkedHashSet<Integer> next, LinkedHashSet<Integer> paused) throws IOException {
        apply(next, paused, null, null);
    }

    private void apply(LinkedHashSet<Integer> next, LinkedHashSet<Integer> paused, Integer oldPort, Integer newPort) throws IOException {
        PortRanges.requireWithinLimit(next);
        clients();
        List<Integer> created = new ArrayList<>();
        try {
            synchronized (ownership) {
                var nextOwners = ownership.nextOwners(next, oldPort, newPort);
                for (int port : next) {
                    validatePort(port);
                    if (!paused.contains(port)) {
                        var listener = ListenServerControl.get(port);
                        if (listener != null && listener.isCanceled()) { ListenServerControl.remove(port); listener = null; }
                        if (listener == null) { bind(port); created.add(port); }
                    }
                }
                ForwardFile.save(file, PortRanges.compact(next), paused, nextOwners);
                ownership.publish(next, paused, nextOwners);
            }
        } catch (IOException | RuntimeException e) {
            created.forEach(ListenServerControl::remove);
            throw e;
        }
        for (int port : ports) if (!next.contains(port) || paused.contains(port)) ListenServerControl.remove(port);
        ports.clear(); ports.addAll(next);
        disabled.clear(); disabled.addAll(paused);
    }

    @Override public synchronized List<ForwardStatus> list() {
        var result = new ArrayList<ForwardStatus>();
        for (int port : ports) {
            var listener = ListenServerControl.get(port);
            var registration = listener == null ? null : listener.registrationSnapshot();
            boolean enabled = !disabled.contains(port);
            boolean online = enabled && listener != null && listener.isAlive();
            boolean failed = enabled && (listener == null || listener.isCanceled());
            result.add(new ForwardStatus(port, registration == null ? null : registration.targetPort(), online,
                    listener == null ? 0 : listener.getSocketPartList().size(), registration == null ? null : registration.targetHost(),
                    enabled, !enabled ? "paused" : online ? "online" : failed ? "error" : "offline",
                    failed ? "监听已停止，请检查端口并重新启用" : "", 0, 0,
                    registration == null ? "" : registration.id(), registration == null ? "" : registration.name(),
                    registration == null ? "" : registration.address(), registration == null ? 0 : registration.connectedAt(),
                    registration == null ? 0 : registration.lastSeenAt()).withMonitoring(ownership.owner(port),
                    !enabled ? HealthSnapshot.waiting("paused")
                    : !online ? HealthSnapshot.waiting("offline") : listener.healthSnapshot()));
        }
        return result;
    }

    @Override public synchronized List<ClientInfo> clients() {
        var grouped = new LinkedHashMap<String, List<ForwardStatus>>();
        for (var status : list()) if (!status.clientId().isEmpty()) grouped.computeIfAbsent(status.clientId(), key -> new ArrayList<>()).add(status);
        var current = new LinkedHashMap<String, ClientInfo>();
        grouped.forEach((id, mappings) -> {
            var newest = mappings.stream().max(Comparator.comparingLong(ForwardStatus::lastSeenAt)).orElseThrow();
            var listener = ListenServerControl.get(newest.port());
            var registration = listener == null ? null : listener.registrationSnapshot();
            var info = new ClientInfo(id, newest.clientName(), newest.clientAddress(), registration == null ? "" : registration.version(),
                    mappings.stream().anyMatch(ForwardStatus::connected),
                    mappings.stream().mapToLong(ForwardStatus::connectedAt).min().orElse(0), newest.lastSeenAt(), List.copyOf(mappings));
            current.put(id, info); knownClients.put(id, info);
        });
        knownClients.forEach((id, info) -> current.putIfAbsent(id,
                new ClientInfo(id, info.name(), info.address(), info.version(), false, info.connectedAt(), info.lastSeenAt(), List.of())));
        while (knownClients.size() > 1024) knownClients.remove(knownClients.keySet().iterator().next());
        return List.copyOf(current.values());
    }

    @Override public DiagnosticReport diagnose(int port) {
        ServerListenThread listener;
        boolean paused;
        synchronized (this) {
            if (!ports.contains(port)) throw new IllegalArgumentException("端口不存在: " + port);
            listener = ListenServerControl.get(port);
            paused = disabled.contains(port);
        }
        var checks = new ArrayList<DiagnosticReport.Check>();
        boolean bound = listener != null && !listener.isCanceled();
        boolean online = bound && listener.isAlive();
        checks.add(new DiagnosticReport.Check("公网端口监听", paused ? "skipped" : bound ? "passed" : "failed",
                paused ? "端口已暂停" : bound ? "服务端已绑定端口 " + port : "端口未正常监听", 0));
        checks.add(new DiagnosticReport.Check("客户端注册", online ? "passed" : "pending",
                online ? "控制连接正常" : "等待客户端连接并注册此端口", 0));
        if (online) {
            try {
                var probe = listener.probeTarget();
                checks.add(new DiagnosticReport.Check("目标服务（由客户端探测）", probe.isReachable() ? "passed" : "failed",
                        TargetAddress.display(probe.getHost(), probe.getPort()) + " · " + probe.getMessage(), probe.getDurationMs()));
            } catch (Exception e) {
                checks.add(new DiagnosticReport.Check("目标服务（由客户端探测）", "skipped",
                        "未取得诊断结果：" + TcpDiagnostics.describe(e), 0));
            }
        } else checks.add(new DiagnosticReport.Check("目标服务", "skipped", "客户端连接后才能执行远程探测", 0));
        return new DiagnosticReport(port, System.currentTimeMillis(), checks, DiagnosticReport.NOTE);
    }

    @Override public synchronized void assignOwner(int port, String clientId) throws IOException {
        if (!ports.contains(port)) throw new IllegalArgumentException("端口不存在: " + port);
        var listener = ListenServerControl.get(port);
        if (listener != null) listener.assignOwner(clientId); else ownership.assign(port, clientId);
    }

    public synchronized void close() {
        for (int port : ports) ListenServerControl.remove(port);
        ports.clear(); disabled.clear(); knownClients.clear();
        if (service != null) { service.cancel(); service = null; }
    }

    private void validatePort(int port) {
        AppSettings.parsePort(Integer.toString(port), "公网端口");
        if (port == settings.handshakePort() || port == settings.adminPort()) throw new IllegalArgumentException("端口与握手或管理端口冲突: " + port);
    }

    private void bind(int port) throws IOException {
        if (ListenServerControl.get(port) != null) throw new IOException("端口已在监听: " + port);
        IListenServerConfig config;
        if (settings.mode() == TunnelMode.SIMPLE) config = new SimpleListenServerConfig(port);
        else if (settings.mode() == TunnelMode.SECRET) {
            var secret = new SecretSimpleListenServerConfig(port);
            secret.setBaseAesKey(settings.keys().aesKey()); secret.setTokenKey(settings.keys().tokenKey()); config = secret;
        } else {
            var secret = new AllSecretSimpleListenServerConfig(port);
            secret.setBaseAesKey(settings.keys().aesKey()); secret.setTokenKey(settings.keys().tokenKey());
            secret.setBasePasswayKey(settings.keys().aesKey()); config = secret;
        }
        var listener = ListenServerControl.createNewListenServer(config);
        if (listener == null)
            throw new IOException("无法监听端口 " + port + "，可能已被其他程序占用或没有权限");
        listener.setOwnership(ownership);
    }
}
