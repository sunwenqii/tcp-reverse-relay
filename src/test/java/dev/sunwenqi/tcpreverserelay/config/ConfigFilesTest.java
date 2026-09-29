package dev.sunwenqi.tcpreverserelay.config;

import dev.sunwenqi.tcpreverserelay.ClientApp;
import dev.sunwenqi.tcpreverserelay.ServerApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFilesTest {
    @TempDir
    Path dir;

    @Test
    void createsBothEditableFilesAndKeepsExistingSettings() throws Exception {
        Path config = dir.resolve("config");
        Path server = ConfigFiles.ensureIn(config, "server.properties");
        Path client = ConfigFiles.ensureIn(config, "client.properties");
        assertEquals(config.resolve("server.properties"), server);
        assertEquals(config.resolve("client.properties"), client);
        assertEquals(18080, AppSettings.server(server).forwards().get(0));
        assertEquals("127.0.0.1", AppSettings.client(client).serverHost());
        assertEquals("", AppSettings.server(server).keys().aesKey());
        assertEquals("", AppSettings.client(client).keys().tokenKey());

        String edited = Files.readString(server).replace("forwards=18080", "forwards=18443");
        Files.writeString(server, edited);
        ConfigFiles.ensureIn(config, "server.properties");
        assertEquals(18443, AppSettings.server(server).forwards().get(0));
    }

    @Test
    void rejectsDirectoryWhereConfigurationFileShouldBe() throws Exception {
        Path config = dir.resolve("config");
        Files.createDirectories(config.resolve("server.properties"));
        assertTrue(assertThrows(IOException.class,
                () -> ConfigFiles.ensureIn(config, "server.properties")).getMessage().contains("不是普通文件"));
    }

    @Test
    void startupRejectsConfigurationPathArguments() {
        assertThrows(IllegalArgumentException.class, () -> ServerApp.main(new String[]{"other.properties"}));
        assertThrows(IllegalArgumentException.class, () -> ClientApp.main(new String[]{"other.properties"}));
    }
}
