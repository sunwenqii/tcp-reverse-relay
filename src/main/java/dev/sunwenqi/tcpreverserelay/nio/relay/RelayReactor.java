package dev.sunwenqi.tcpreverserelay.nio.relay;

import java.io.IOException;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/** Owns encrypted-relay channel state; crypto workers never wait on socket I/O. */
@Slf4j
public final class RelayReactor implements AutoCloseable {
    private final Selector selector;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Set<EncryptedRelay> relays = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor crypto;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread thread;
    private static final class Shared {
        static final RelayReactor INSTANCE = create();
        private static RelayReactor create() {
            try {
                var reactor = new RelayReactor();
                Runtime.getRuntime().addShutdownHook(new Thread(reactor::close, "relay-nio-shutdown"));
                return reactor;
            } catch (IOException e) { throw new ExceptionInInitializerError(e); }
        }
    }
    public static RelayReactor shared() { return Shared.INSTANCE; }
    public RelayReactor() throws IOException {
        selector = Selector.open();
        int workers = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        crypto = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
                task -> { Thread worker = new Thread(task, "relay-crypto"); worker.setDaemon(true); return worker; });
        thread = new Thread(this::run, "relay-encrypted-selector"); thread.setDaemon(true); thread.start();
    }
    Selector selector() { return selector; }
    void add(EncryptedRelay relay) { relays.add(relay); }
    void remove(EncryptedRelay relay) { relays.remove(relay); }
    synchronized void execute(Runnable command) {
        if (closed.get()) throw new RejectedExecutionException("Encrypted relay reactor is closed");
        commands.add(command); selector.wakeup();
    }
    boolean transform(Runnable job) {
        try { crypto.execute(job); return true; } catch (RejectedExecutionException e) { return false; }
    }
    private void run() {
        try {
            while (!closed.get()) {
                Runnable command;
                while ((command = commands.poll()) != null) runCommand(command);
                for (var relay : List.copyOf(relays)) relay.pump();
                selector.select(50);
                var iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    var key = iterator.next(); iterator.remove();
                    try {
                        if (key.isValid()) ((EncryptedRelay.Endpoint) key.attachment()).ready(key.readyOps());
                    } catch (java.nio.channels.CancelledKeyException ignored) { /* A concurrently closed channel. */ }
                }
            }
        } catch (Exception e) { log.error("加密通道事件循环异常", e); }
        finally {
            synchronized (this) { closed.set(true); }
            Runnable command;
            while ((command = commands.poll()) != null) { try { command.run(); } catch (RuntimeException ignored) {} }
            for (var relay : List.copyOf(relays)) relay.finish(new IOException("加密转发已停止"));
            crypto.shutdownNow();
            try { selector.close(); } catch (IOException ignored) {}
        }
    }
    private void runCommand(Runnable command) {
        try { command.run(); } catch (RuntimeException e) { log.error("加密通道任务失败", e); }
    }
    @Override public void close() {
        synchronized (this) { if (!closed.compareAndSet(false, true)) return; }
        selector.wakeup();
        if (Thread.currentThread() != thread) try { thread.join(5000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
