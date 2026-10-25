package dev.sunwenqi.tcpreverserelay.config;

import dev.sunwenqi.tcpreverserelay.clientside.ClientRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagementConfigTest {
    @TempDir Path dir;
    private Path client(String forwards, String extra) throws Exception {
        Path file = dir.resolve("client.properties");
        Files.writeString(file, "mode=simple\nserver.host=127.0.0.1\nhandshake.port=18001\nadmin.port=19091\nforwards=" + forwards + "\n" + extra);
        return file;
    }
    @Test void migratesLegacyMappingsAndPersistsStableIdentity() throws Exception {
        Path file = client("18080:8080,18081:nas.local:9000,18082:[::1]:8081", "disabled.forwards=18081\n");
        var first = AppSettings.client(file);
        assertEquals("127.0.0.1", first.forwards().get(0).targetHost());
        assertEquals("nas.local", first.forwards().get(1).targetHost());
        assertEquals("::1", first.forwards().get(2).targetHost());
        assertTrue(first.disabledForwards().contains(18081));
        assertEquals(first.clientId(), AppSettings.client(file).clientId());
        assertTrue(Files.readString(file).contains("client.id="));
    }
    @Test void rejectsInvalidTargetsAndInvalidPausedPorts() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AppSettings.parseMappings("18080:http://localhost:8080", 19091));
        assertThrows(IllegalArgumentException.class, () -> AppSettings.parseMappings("18080:::1:8080", 19091));
        assertThrows(IllegalArgumentException.class, () -> TargetAddress.validate("256.1.1.1"));
        assertThrows(IllegalArgumentException.class, () -> TargetAddress.validate("host/path"));
        Path file = client("18080:8080", "disabled.forwards=18081\n");
        assertThrows(IllegalArgumentException.class, () -> AppSettings.client(file));
    }
    @Test void batchPreviewAndRejectedBatchDoNotMutateConfiguration() throws Exception {
        Path file = client("18080:8080", "disabled.forwards=18080\n");
        var runtime = new ClientRuntime(file, AppSettings.client(file));
        runtime.start();
        try {
            String before = Files.readString(file);
            assertEquals(2, runtime.batch("add", "18081:localhost:8081\n18082:127.0.0.2:8082", true).ports().size());
            assertEquals(before, Files.readString(file));
            assertThrows(IllegalArgumentException.class, () -> runtime.batch("add", "18081:8081,18080:8080", false));
            assertEquals(before, Files.readString(file));
            assertEquals(1, runtime.list().size());
            runtime.batch("delete", "18080", false);
            assertTrue(AppSettings.client(file).forwards().isEmpty());
        } finally { runtime.close(); }
    }
    @Test void passwordChangePreservesOtherSettingsAndRejectsWrongCurrentPassword() throws Exception {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "# comment\nADMIN_TOKEN=old-pass-123\nforwards=18080\nadmin.public=true\n");
        assertThrows(IllegalArgumentException.class, () -> AdminSettingsFile.changePassword(file, "wrong", "new-pass-123"));
        assertEquals("old-pass-123", AdminTokenFile.readInFile(file));
        AdminSettingsFile.changePassword(file, "old-pass-123", "new-pass-123");
        assertEquals("new-pass-123", AdminTokenFile.readInFile(file));
        assertTrue(Files.readString(file).contains("forwards=18080"));
        assertTrue(Files.readString(file).startsWith("# comment"));
    }
}
