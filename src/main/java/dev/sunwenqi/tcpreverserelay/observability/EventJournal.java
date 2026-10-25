package dev.sunwenqi.tcpreverserelay.observability;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded recent index with a single asynchronous rotating-file writer. */
public final class EventJournal implements AutoCloseable {
    public static final int MAX_RECORDS = 10_000;
    public static final long RETENTION_MS = Duration.ofDays(7).toMillis();
    private final Path directory;
    private final SecretRedactor redactor;
    private final long maxFileBytes;
    private final int archives;
    private final ArrayDeque<JournalEntry> recent = new ArrayDeque<>();
    private final ThreadPoolExecutor writer;
    private final AtomicLong dropped = new AtomicLong();
    private long nextId = 1, currentSize;
    private volatile String storageError = "";
    private volatile boolean closed;
    private FileChannel lockChannel;
    private FileLock fileLock;

    public EventJournal(Path directory, SecretRedactor redactor) throws IOException {
        this(directory, redactor, 5L * 1024 * 1024, 3);
    }
    EventJournal(Path directory, SecretRedactor redactor, long maxFileBytes, int archives) throws IOException {
        this.directory = directory.toAbsolutePath(); this.redactor = redactor;
        this.maxFileBytes = maxFileBytes; this.archives = archives;
        try {
            Files.createDirectories(this.directory);
            lockChannel = FileChannel.open(this.directory.resolve("journal.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
            if (fileLock == null) throw new IOException("日志目录已被占用");
            restore();
            currentSize = Files.exists(file(0)) ? Files.size(file(0)) : 0;
            if (currentSize > 0) {
                try (var input = new RandomAccessFile(file(0).toFile(), "r")) {
                    input.seek(currentSize - 1);
                    if (input.read() != '\n') { Files.writeString(file(0), "\n", StandardOpenOption.APPEND); currentSize++; }
                }
            }
        } catch (IOException | OverlappingFileLockException e) {
            storageError = "日志目录不可用或已被其他进程占用，当前记录暂存在内存中；请检查目录权限或磁盘空间";
            if (fileLock == null && lockChannel != null) { lockChannel.close(); lockChannel = null; }
        }
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2048),
                task -> { Thread thread = new Thread(task, "relay-event-writer"); thread.setDaemon(true); return thread; });
    }
    private Path file(int index) { return directory.resolve(index == 0 ? "journal.jsonl" : "journal.jsonl." + index); }

    public synchronized void append(String kind, String level, String category, Integer port, List<Integer> ports,
                                    String message, String detail, String source) {
        if (closed) return;
        var entry = new JournalEntry(nextId++, System.currentTimeMillis(), kind, level, category, port,
                ports == null ? List.of() : List.copyOf(ports), clip(message, 1500), clip(detail, 12_000), clip(source, 200));
        recent.addLast(entry); trim();
        try { writer.execute(() -> persist(entry)); }
        catch (RejectedExecutionException e) { dropped.incrementAndGet(); }
    }
    private String clip(String value, int length) {
        String safe = redactor.clean(value);
        return safe.length() <= length ? safe : safe.substring(0, length) + "…（已截断）";
    }
    private void trim() {
        long oldest = System.currentTimeMillis() - RETENTION_MS;
        while (!recent.isEmpty() && (recent.size() > MAX_RECORDS || recent.peekFirst().time() < oldest)) recent.removeFirst();
    }
    private void restore() throws IOException {
        for (int index = archives; index >= 0; index--) {
            Path path = file(index);
            if (!Files.isRegularFile(path)) continue;
            if (Files.getLastModifiedTime(path).toMillis() < System.currentTimeMillis() - RETENTION_MS) {
                Files.delete(path); continue;
            }
            if (Files.size(path) > maxFileBytes + 128 * 1024) {
                storageError = "发现超出大小限制的日志文件，已跳过历史读取"; continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject value = JSON.parseObject(line);
                        long id = value.getLongValue("id"), time = value.getLongValue("time");
                        String kind = value.getString("kind"), level = value.getString("level");
                        if (id <= 0 || time <= 0 || !Set.of("event", "log").contains(kind)
                                || !Set.of("INFO", "WARN", "ERROR").contains(level)) throw new IllegalArgumentException();
                        List<Integer> ports = value.getJSONArray("ports") == null ? List.of() : value.getJSONArray("ports").toJavaList(Integer.class);
                        recent.addLast(new JournalEntry(id, time, kind, level, value.getString("category"), value.getInteger("port"),
                                List.copyOf(ports), clip(value.getString("message"), 1500), clip(value.getString("detail"), 12_000), clip(value.getString("source"), 200)));
                        nextId = Math.max(nextId, id + 1); trim();
                    } catch (RuntimeException ignored) { storageError = "部分历史记录不完整，已跳过；其余记录仍可查看"; }
                }
            }
        }
    }
    private void persist(JournalEntry entry) {
        if (fileLock == null) { dropped.incrementAndGet(); return; }
        try {
            byte[] data = (JSON.toJSONString(asMap(entry)) + "\n").getBytes(StandardCharsets.UTF_8);
            if (currentSize > 0 && currentSize + data.length > maxFileBytes) rotate();
            Files.write(file(0), data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentSize += data.length;
        } catch (IOException e) {
            dropped.incrementAndGet(); storageError = "日志写入失败，请检查 logs 目录权限或磁盘空间";
        }
    }
    private void rotate() throws IOException {
        Files.deleteIfExists(file(archives));
        for (int index = archives - 1; index >= 0; index--)
            if (Files.exists(file(index))) Files.move(file(index), file(index + 1), StandardCopyOption.REPLACE_EXISTING);
        currentSize = 0;
        for (int index = 1; index <= archives; index++)
            if (Files.exists(file(index)) && Files.getLastModifiedTime(file(index)).toMillis() < System.currentTimeMillis() - RETENTION_MS)
                Files.delete(file(index));
    }
    public record Filter(String kind, String level, Integer port, String keyword, long from, long to, long before, int limit) {
        public Filter {
            if (!Set.of("all", "event", "log").contains(kind)) throw new IllegalArgumentException("kind 只能是 all、event 或 log");
            if (!Set.of("all", "INFO", "WARN", "ERROR").contains(level)) throw new IllegalArgumentException("无效的日志级别");
            if (port != null && (port < 1 || port > 65535)) throw new IllegalArgumentException("端口需为 1 至 65535");
            if (keyword == null || keyword.length() > 200) throw new IllegalArgumentException("关键词最多 200 个字符");
            if (from < 0 || to < 0 || before < 0 || (to > 0 && from > to)) throw new IllegalArgumentException("无效的时间或分页范围");
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("每页条数需为 1 至 100");
        }
    }
    public Map<String, Object> query(Filter filter) {
        List<JournalEntry> snapshot;
        synchronized (this) { trim(); snapshot = List.copyOf(recent); }
        var rows = new ArrayList<Map<String, Object>>();
        int total = 0; boolean more = false;
        String keyword = filter.keyword().toLowerCase(Locale.ROOT);
        for (int index = snapshot.size() - 1; index >= 0; index--) {
            var item = snapshot.get(index);
            if (!filter.kind().equals("all") && !filter.kind().equals(item.kind())) continue;
            if (!filter.level().equals("all") && !filter.level().equals(item.level())) continue;
            if (filter.port() != null && !filter.port().equals(item.port()) && !item.ports().contains(filter.port())) continue;
            if (item.time() < filter.from() || (filter.to() > 0 && item.time() > filter.to())) continue;
            if (!keyword.isEmpty() && !redactor.clean(item.message() + " " + item.detail() + " " + item.source() + " " + item.category()).toLowerCase(Locale.ROOT).contains(keyword)) continue;
            total++;
            if (filter.before() > 0 && item.id() >= filter.before()) continue;
            if (rows.size() < filter.limit()) rows.add(asMap(item)); else more = true;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("entries", rows); result.put("total", total); result.put("retained", snapshot.size());
        result.put("hasMore", more); result.put("nextCursor", more && !rows.isEmpty() ? rows.get(rows.size() - 1).get("id") : 0);
        result.put("maxRecords", MAX_RECORDS); result.put("retentionDays", 7);
        result.put("storage", Map.of("pending", writer.getQueue().size() + writer.getActiveCount(), "dropped", dropped.get(), "warning", storageError));
        return result;
    }
    private Map<String, Object> asMap(JournalEntry entry) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", entry.id()); result.put("time", entry.time()); result.put("kind", entry.kind()); result.put("level", entry.level());
        result.put("category", entry.category()); result.put("port", entry.port()); result.put("ports", entry.ports());
        result.put("message", redactor.clean(entry.message())); result.put("detail", redactor.clean(entry.detail())); result.put("source", redactor.clean(entry.source()));
        return result;
    }
    public void addSecret(String secret) { redactor.add(secret); }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; writer.shutdown(); }
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) dropped.addAndGet(writer.shutdownNow().size()); }
        catch (InterruptedException e) { dropped.addAndGet(writer.shutdownNow().size()); Thread.currentThread().interrupt(); }
        finally {
            try { if (fileLock != null) fileLock.release(); } catch (IOException ignored) {}
            try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) {}
        }
    }
}
