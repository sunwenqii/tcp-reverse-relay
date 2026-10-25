package dev.sunwenqi.tcpreverserelay.observability;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** A process normally has one role; separate role sinks also support embedded use. */
public final class RuntimeEvents {
    private static final Map<String, EventJournal> JOURNALS = new ConcurrentHashMap<>();
    private RuntimeEvents() {}
    public static synchronized Session open(Path configFile, String role, String... secrets) throws IOException {
        if (JOURNALS.containsKey(role)) throw new IllegalStateException("日志记录已启动: " + role);
        Path parent = configFile.toAbsolutePath().getParent();
        Path root = parent.getFileName().toString().equals("config") ? parent.getParent() : parent;
        var redactor = new SecretRedactor();
        for (String secret : secrets) redactor.add(secret);
        var journal = new EventJournal(root.resolve("logs").resolve(role), redactor);
        if (JOURNALS.putIfAbsent(role, journal) != null) { journal.close(); throw new IllegalStateException("日志记录已启动: " + role); }
        return new Session(role, journal);
    }
    public static void event(String role, String level, String category, Integer port, String message, String detail) {
        batch(role, level, category, port == null ? List.of() : List.of(port), message, detail);
    }
    public static void batch(String role, String level, String category, List<Integer> ports, String message, String detail) {
        EventJournal journal = JOURNALS.get(role);
        if (journal != null) journal.append("event", level, category, ports.size() == 1 ? ports.get(0) : null, ports, message, detail, "runtime");
    }
    static void log(String level, Integer port, String message, String detail, String source) {
        for (var journal : JOURNALS.values()) journal.append("log", level, "程序日志", port, List.of(), message, detail, source);
    }
    static boolean enabled() { return !JOURNALS.isEmpty(); }
    public static final class Session implements AutoCloseable {
        private final String role;
        private final EventJournal journal;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread shutdownHook;
        private Session(String role, EventJournal journal) {
            this.role = role; this.journal = journal;
            shutdownHook = new Thread(this::close, "relay-log-shutdown-" + role);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
        public EventJournal journal() { return journal; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) {}
            journal.close();
            JOURNALS.remove(role, journal);
        }
    }
}
