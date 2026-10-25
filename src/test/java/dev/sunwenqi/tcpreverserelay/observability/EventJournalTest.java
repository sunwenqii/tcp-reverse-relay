package dev.sunwenqi.tcpreverserelay.observability;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class EventJournalTest {
    @TempDir Path root;
    private EventJournal.Filter filter(String kind, Integer port, String word, long before, int limit) {
        return new EventJournal.Filter(kind, "all", port, word, 0, 0, before, limit);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> entries(Map<String,Object> result) { return (List<Map<String,Object>>) result.get("entries"); }

    @Test void filtersAndStableCursorSurviveNewEntriesAndRestart() throws Exception {
        var redactor = new SecretRedactor(); redactor.add("fixture-secret-123");
        try (var journal = new EventJournal(root, redactor)) {
            for (int index=0; index<5; index++) journal.append("event", "INFO", "配置", 18080, List.of(), "变更 " + index, "", "test");
            journal.append("log", "ERROR", "程序日志", 18443, List.of(), "失败 fixture-secret-123", "ADMIN_TOKEN=hidden-value", "test");
            journal.append("event", "WARN", "批量配置", null, List.of(18080,18443), "批量暂停", "", "test");
            var first = journal.query(filter("event", 18080, "", 0, 2));
            assertEquals(6, first.get("total")); assertTrue((Boolean)first.get("hasMore"));
            long cursor = ((Number)first.get("nextCursor")).longValue();
            journal.append("event", "INFO", "配置", 18080, List.of(), "新事件", "", "test");
            var older = entries(journal.query(filter("event", 18080, "", cursor, 2)));
            assertTrue(older.stream().allMatch(row -> ((Number)row.get("id")).longValue()<cursor));
            assertEquals(1, journal.query(filter("log", 18443, "失败", 0, 10)).get("total"));
        }
        String disk=Files.readString(root.resolve("journal.jsonl"));
        assertFalse(disk.contains("fixture-secret-123")); assertFalse(disk.contains("hidden-value"));
        try (var restored = new EventJournal(root, redactor)) {
            assertEquals(8, restored.query(filter("all", null, "", 0, 100)).get("total"));
            assertEquals("新事件", entries(restored.query(filter("event", 18080, "", 0, 1))).get(0).get("message"));
        }
    }
    @Test void rotatesFilesAndRestoresOnlyRetainedHistory() throws Exception {
        try (var journal = new EventJournal(root, new SecretRedactor(), 1024, 3)) {
            for (int index=0;index<20;index++) journal.append("event", "INFO", "配置", 18080, List.of(), "事件 " + index, "x".repeat(250), "test");
        }
        try (var files=Files.list(root)) {
            var paths=files.filter(file -> file.getFileName().toString().startsWith("journal.jsonl")).toList();assertTrue(paths.size()<=4);
            for(var file:paths)assertTrue(Files.size(file)<=1024);
        }
        try (var restored = new EventJournal(root, new SecretRedactor(), 1024, 3)) {
            var rows=entries(restored.query(filter("all",null,"",0,100)));
            assertTrue(rows.size()<20);assertEquals("事件 19",rows.get(0).get("message"));
            restored.append("event","INFO","配置",null,List.of(),"重启后","","test");
            assertEquals(21L,entries(restored.query(filter("all",null,"",0,1))).get(0).get("id"));
        }
    }
    @Test void skipsBrokenTailAndSeparatesNextWrite() throws Exception {
        try(var journal=new EventJournal(root,new SecretRedactor())) { journal.append("event","INFO","启动",null,List.of(),"完整记录","","test"); }
        Files.writeString(root.resolve("journal.jsonl"),"{\"id\":999",StandardOpenOption.APPEND);
        try(var journal=new EventJournal(root,new SecretRedactor())) {
            assertEquals(1,journal.query(filter("all",null,"",0,100)).get("total"));
            assertFalse(((Map<?,?>)journal.query(filter("all",null,"",0,100)).get("storage")).get("warning").toString().isEmpty());
            journal.append("event","INFO","启动",null,List.of(),"尾部损坏后继续写入","","test");
        }
        try(var restored=new EventJournal(root,new SecretRedactor())) { assertEquals(2,restored.query(filter("all",null,"",0,100)).get("total")); }
    }
    @Test void expiresOldRecordsAndRejectsInvalidFilters() throws Exception {
        var value=new LinkedHashMap<String,Object>();value.put("id",1);value.put("time",System.currentTimeMillis()-Duration.ofDays(8).toMillis());
        value.put("kind","event");value.put("level","INFO");value.put("message","过期");
        Files.writeString(root.resolve("journal.jsonl"),JSON.toJSONString(value)+"\n");
        try(var journal=new EventJournal(root,new SecretRedactor())) { assertEquals(0,journal.query(filter("all",null,"",0,100)).get("total")); }
        assertThrows(IllegalArgumentException.class,()->new EventJournal.Filter("bad","all",null,"",0,0,0,50));
        assertThrows(IllegalArgumentException.class,()->new EventJournal.Filter("all","all",null,"",20,10,0,50));
        assertThrows(IllegalArgumentException.class,()->filter("all",99999,"",0,101));
    }
    @Test void concurrentWritersProduceUniqueDurableRecords() throws Exception {
        try(var journal=new EventJournal(root,new SecretRedactor())) {
            var pool=Executors.newFixedThreadPool(4);
            try { for(int index=0;index<400;index++)pool.submit(()->journal.append("event","INFO","并发",18080,List.of(),"连接变化","","test")); }
            finally {pool.shutdown();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
            assertEquals(400,journal.query(filter("all",null,"",0,100)).get("total"));
        }
        try(var restored=new EventJournal(root,new SecretRedactor())) {
            var ids=new HashSet<Long>();long cursor=0;
            do {var result=restored.query(filter("all",null,"",cursor,63));for(var row:entries(result))assertTrue(ids.add(((Number)row.get("id")).longValue()));cursor=((Number)result.get("nextCursor")).longValue();}while(cursor>0);
            assertEquals(400,ids.size());
        }
    }
    @Test void unavailableDiskKeepsMemoryAndReportsStorageFailure() throws Exception {
        Path blocked=root.resolve("blocked");Files.writeString(blocked,"file");
        var journal=new EventJournal(blocked,new SecretRedactor());
        journal.append("event","INFO","启动",null,List.of(),"仍可查看","","test");journal.close();
        var result=journal.query(filter("all",null,"",0,10));
        assertEquals(1,result.get("total"));assertEquals(1L,((Map<?,?>)result.get("storage")).get("dropped"));
        assertFalse(((Map<?,?>)result.get("storage")).get("warning").toString().isBlank());
    }
    @Test void secondWriterCannotOverwriteAnActiveJournal() throws Exception {
        try(var owner=new EventJournal(root,new SecretRedactor());var second=new EventJournal(root,new SecretRedactor())) {
            owner.append("event","INFO","启动",null,List.of(),"首个实例","","test");
            second.append("event","INFO","启动",null,List.of(),"第二个实例","","test");
            assertFalse(((Map<?,?>)second.query(filter("all",null,"",0,10)).get("storage")).get("warning").toString().isBlank());
        }
        String disk=Files.readString(root.resolve("journal.jsonl"));assertTrue(disk.contains("首个实例"));assertFalse(disk.contains("第二个实例"));
    }
}
