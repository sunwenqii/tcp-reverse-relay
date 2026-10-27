package dev.sunwenqi.tcpreverserelay.health;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.forward.TcpDiagnostics;
import dev.sunwenqi.tcpreverserelay.model.interactive.TargetProbeResult;
import dev.sunwenqi.tcpreverserelay.observability.RuntimeEvents;

/** Bounded probes with generation checks: an old target can never overwrite a new target's status. */
public final class HealthMonitor implements AutoCloseable {
    @FunctionalInterface public interface Probe { TargetProbeResult run(String host, int port, int timeout); }
    private static final class Entry {
        final AppSettings.Mapping mapping; HealthSnapshot snapshot; long due; boolean busy;
        Entry(AppSettings.Mapping mapping, HealthSettings settings) {
            this.mapping = mapping;
            snapshot = new HealthSnapshot(settings.enabled() ? "pending" : "disabled", mapping.targetHost(), mapping.localPort(),
                    0, 0, 0, settings.intervalSeconds(), "");
        }
    }
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "relay-health-clock"));
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), r -> daemon(r, "relay-health-probe"));
    private final Probe probe;
    private final BiConsumer<Integer, HealthSnapshot> listener;
    private HealthSettings settings;
    private boolean closed;
    public HealthMonitor(HealthSettings settings, BiConsumer<Integer, HealthSnapshot> listener) {
        this(settings, listener, TcpDiagnostics::probe);
    }
    public HealthMonitor(HealthSettings settings, BiConsumer<Integer, HealthSnapshot> listener, Probe probe) {
        this.settings = settings; this.listener = listener; this.probe = probe;
        clock.scheduleWithFixedDelay(this::tick, 100, 250, TimeUnit.MILLISECONDS);
    }
    public synchronized void reconcile(Collection<AppSettings.Mapping> mappings, Set<Integer> disabled) {
        Set<Integer> wanted = new HashSet<>();
        for (var mapping : mappings) if (!disabled.contains(mapping.remotePort())) {
            int port = mapping.remotePort(); wanted.add(port);
            Entry old = entries.get(port);
            if (old == null || !old.mapping.equals(mapping)) {
                Entry next = new Entry(mapping, settings); entries.put(port, next); listener.accept(port, next.snapshot);
            }
        }
        entries.keySet().retainAll(wanted);
    }
    public synchronized void configure(HealthSettings settings) {
        this.settings = settings;
        var mappings = entries.values().stream().map(e -> e.mapping).toList(); entries.clear(); reconcile(mappings, Set.of());
    }
    public synchronized HealthSnapshot snapshot(int port) {
        Entry entry = entries.get(port);
        if (entry == null) return HealthSnapshot.waiting("paused");
        var snapshot = entry.snapshot;
        return snapshot.checkedAt() == 0 ? snapshot : snapshot.staleAt(snapshot.checkedAt(), System.currentTimeMillis());
    }
    private synchronized void tick() {
        if (closed || !settings.enabled()) return;
        long now = System.currentTimeMillis();
        for (var item : entries.entrySet()) {
            Entry entry = item.getValue(); if (entry.busy || entry.due > now) continue;
            entry.busy = true;
            int port = item.getKey(), timeout = settings.timeoutMillis();
            try { workers.execute(() -> {
                TargetProbeResult result;
                try { result = probe.run(entry.mapping.targetHost(), entry.mapping.localPort(), timeout); }
                catch (RuntimeException e) {
                    result = new TargetProbeResult(); result.setHost(entry.mapping.targetHost()); result.setPort(entry.mapping.localPort());
                    result.setMessage(TcpDiagnostics.describe(e));
                }
                complete(port, entry, result);
            }); } catch (RejectedExecutionException e) { entry.busy = false; break; }
        }
    }
    private synchronized void complete(int port, Entry expected, TargetProbeResult result) {
        if (closed || entries.get(port) != expected) return;
        expected.busy = false; record(port, expected, result);
    }
    public synchronized void record(int port, TargetProbeResult result) {
        Entry entry = entries.get(port);
        if (entry != null && settings.enabled() && entry.mapping.targetHost().equals(result.getHost()) && entry.mapping.localPort() == result.getPort())
            record(port, entry, result);
    }
    private void record(int port, Entry entry, TargetProbeResult result) {
        // Local scheduling pressure is not evidence that the target is down. Old results expire naturally.
        if ("诊断任务繁忙，请稍后重试".equals(result.getMessage())) { entry.due = System.currentTimeMillis() + 1000; return; }
        int failures = result.isReachable() ? 0 : Math.min(1_000_000, entry.snapshot.failures() + 1);
        String state = result.isReachable() ? "up" : failures >= settings.failureThreshold() ? "down" : "suspect";
        String previous = entry.snapshot.state();
        entry.snapshot = new HealthSnapshot(state, entry.mapping.targetHost(), entry.mapping.localPort(), System.currentTimeMillis(),
                Math.min(60_000, Math.max(0, result.getDurationMs())), failures, settings.intervalSeconds(), result.getMessage());
        entry.due = System.currentTimeMillis() + settings.intervalSeconds() * 1000L;
        listener.accept(port, entry.snapshot);
        if (!previous.equals(state) && (state.equals("down") || state.equals("up") && (previous.equals("down") || previous.equals("suspect"))))
            RuntimeEvents.event("client", state.equals("down") ? "WARN" : "INFO", "目标连通性", port,
                    state.equals("down") ? "目标服务无法建立 TCP 连接" : "目标服务已恢复 TCP 连通", result.getMessage());
    }
    @Override public synchronized void close() { closed = true; entries.clear(); clock.shutdownNow(); workers.shutdownNow(); }
    private static Thread daemon(Runnable job, String name) { var t = new Thread(job, name); t.setDaemon(true); return t; }
}
