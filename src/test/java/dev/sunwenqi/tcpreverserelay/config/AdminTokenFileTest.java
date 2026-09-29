package dev.sunwenqi.tcpreverserelay.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdminTokenFileTest {
    @TempDir
    Path dir;

    @Test
    void emptyPasswordWaitsForFirstVisitAndPreservesOtherSettings() throws Exception {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "# saved by user\nmode=simple\nAES_KEY=existing\nforwards=18080\nADMIN_TOKEN=\nadmin.public=false\n");
        assertEquals("", AdminTokenFile.resolve(file));
        assertTrue(Files.readString(file).contains("ADMIN_TOKEN=\n"));
        AdminSettingsFile.setPassword(file, "my-password-8");
        assertEquals("my-password-8", AdminTokenFile.resolve(file));
        ForwardFile.save(file, List.of("18080-18082"));
        assertEquals("my-password-8", AdminTokenFile.readInFile(file));
        assertTrue(Files.readString(file).contains("AES_KEY=existing"));
        assertTrue(Files.readString(file).contains("# saved by user"));
    }

    @Test
    void validatesPasswordAndKeepsExistingValue() throws Exception {
        Path file = dir.resolve("client.properties");
        Files.writeString(file, "ADMIN_TOKEN=\nforwards=\n");
        assertThrows(IllegalArgumentException.class, () -> AdminSettingsFile.setPassword(file, "short"));
        assertThrows(IllegalArgumentException.class, () -> AdminSettingsFile.setPassword(file, " leading-space"));
        AdminSettingsFile.setPassword(file, "eightchars");
        assertThrows(IllegalStateException.class, () -> AdminSettingsFile.setPassword(file, "anotherpass"));
        assertEquals("eightchars", AdminTokenFile.readInFile(file));
        Files.writeString(file, "ADMIN_TOKEN=one\nADMIN_TOKEN=two\n");
        assertTrue(assertThrows(IOException.class, () -> AdminTokenFile.readInFile(file)).getMessage().contains("只能有一行"));
    }

    @Test
    void savesPublicSwitchAndAcceptsOlderConfigWithoutIt() throws Exception {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "mode=simple\nhandshake.port=9000\nadmin.port=19090\nforwards=10000-10003\n");
        assertFalse(AdminSettingsFile.publicAccess(file));
        AdminSettingsFile.setPublicAccess(file, true);
        assertTrue(AdminSettingsFile.publicAccess(file));
        assertEquals(List.of(10000, 10001, 10002), AppSettings.server(file).forwards());
        AdminSettingsFile.setPublicAccess(file, false);
        assertFalse(AdminSettingsFile.publicAccess(file));
        Files.writeString(file, Files.readString(file).replace("admin.public=false", "admin.public=maybe"));
        assertThrows(IllegalArgumentException.class, () -> AppSettings.server(file));
    }
}