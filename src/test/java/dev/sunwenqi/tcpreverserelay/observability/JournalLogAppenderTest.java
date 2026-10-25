package dev.sunwenqi.tcpreverserelay.observability;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class JournalLogAppenderTest {
    @TempDir Path root;
    @Test void capturesExceptionDetailsAndOmitsSecretsAndHandshakePayloads() throws Exception {
        Path config=root.resolve("client.properties");Files.writeString(config,"");
        try(var session=RuntimeEvents.open(config,"client","fixture-secret-123")) {
            var logger=LoggerFactory.getLogger("dev.sunwenqi.tcpreverserelay.observability.AppenderFixture");
            logger.debug("should not be recorded");
            logger.warn("端口 18080 failed fixture-secret-123",new IllegalStateException("ADMIN_TOKEN=another-secret"));
            logger.info("SecretInteractiveModel(encrypt=sensitive-payload)");
            String records=JSON.toJSONString(session.journal().query(new EventJournal.Filter("log","all",null,"",0,0,0,100)));
            assertTrue(records.contains("IllegalStateException"));assertTrue(records.contains("18080"));
            assertFalse(records.contains("fixture-secret-123"));assertFalse(records.contains("another-secret"));
            assertFalse(records.contains("sensitive-payload"));assertFalse(records.contains("should not be recorded"));
        }
        String disk=Files.readString(root.resolve("logs/client/journal.jsonl"));
        assertFalse(disk.contains("fixture-secret-123"));assertFalse(disk.contains("sensitive-payload"));
    }
}
