package dev.sunwenqi.tcpreverserelay.config;

import dev.sunwenqi.tcpreverserelay.serverside.ServerRuntime;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ListenServerControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {
    @TempDir
    Path dir;

    @Test
    void validatesAndLoadsServerAndClientFiles() throws Exception {
        Path server = dir.resolve("server.properties");
        Files.writeString(server, "mode=secretAll\nhandshake.port=10010\nadmin.port=19090\nforwards=18080,18443\n");
        assertEquals(List.of(18080, 18443), AppSettings.server(server).forwards());
        Path client = dir.resolve("client.properties");
        Files.writeString(client, "mode=secretAll\nserver.host=example.com\nhandshake.port=10010\nadmin.port=19091\nforwards=18080:8080,18443:8443\n");
        assertEquals(8443, AppSettings.client(client).forwards().get(1).localPort());
    }

    @Test
    void rejectsInvalidOrConflictingPorts() throws Exception {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "mode=secretAll\nhandshake.port=10010\nadmin.port=19090\nforwards=10010\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSettings.server(file)).getMessage().contains("冲突"));
        Files.writeString(file, "mode=secretAll\nhandshake.port=10010\nadmin.port=19090\nforwards=70000\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSettings.server(file)).getMessage().contains("65535"));
        Files.writeString(file, "mode=secretAll\nhandshake.port=10010\nadmin.port=19090\nforward=18080\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSettings.server(file)).getMessage().contains("未知"));
        Files.writeString(file, "mode=secretAll\nhandshake.port=10010\nadmin.port=19090\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSettings.server(file)).getMessage().contains("forwards="));
        Path client = dir.resolve("client.properties");
        Files.writeString(client, "mode=secretAll\nserver.host=example.com\nhandshake.port=10010\nadmin.port=19091\nforwards=18080:19091\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSettings.client(client)).getMessage().contains("管理端口"));
    }

    @Test
    void savesMappingAndPreservesOtherSettingsAndComments() throws Exception {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "# comment\nmode=simple\nhandshake.port=10010\nadmin.port=19090\nforwards=18080\n");
        ForwardFile.save(file, List.of("18443"));
        assertEquals(List.of(18443), AppSettings.server(file).forwards());
        assertTrue(Files.readString(file).contains("# comment"));
    }

    @Test
    void occupiedPortDoesNotChangeSavedSettings() throws Exception {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "mode=simple\nhandshake.port=10010\nadmin.port=19090\nforwards=\n");
        ServerRuntime runtime = new ServerRuntime(file, AppSettings.server(file));
        try (ServerSocket occupied = new ServerSocket(0)) {
            assertThrows(java.io.IOException.class, () -> runtime.upsert(null, occupied.getLocalPort()));
            assertEquals(List.of(), AppSettings.server(file).forwards());
        } finally {
            runtime.close();
        }
    }

    @Test
    void serverAddsAndDeletesPortImmediately() throws Exception {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "mode=simple\nhandshake.port=10010\nadmin.port=19090\nforwards=\n");
        ServerRuntime runtime = new ServerRuntime(file, AppSettings.server(file));
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        try {
            runtime.upsert(null, port);
            assertNotNull(ListenServerControl.get(port));
            assertEquals(List.of(port), AppSettings.server(file).forwards());
            runtime.delete(port);
            assertNull(ListenServerControl.get(port));
            assertEquals(List.of(), AppSettings.server(file).forwards());
        } finally {
            runtime.close();
        }
    }

    @Test
    void serverRangeHasExclusiveEndAndTwoThousandPorts() throws Exception {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "mode=secretAll\nhandshake.port=9000\nadmin.port=19090\nforwards=10000-12000\n");
        List<Integer> ports = AppSettings.server(file).forwards();
        assertEquals(2000, ports.size());
        assertEquals(10000, ports.get(0));
        assertEquals(11999, ports.get(1999));
        assertFalse(ports.contains(12000));
    }

    @Test
    void serverRangesRejectOverlapConflictAndOversizedSets() throws Exception {
        Path file = dir.resolve("server.properties");
        String prefix = "mode=secretAll\nhandshake.port=10010\nadmin.port=19090\nforwards=";
        Files.writeString(file, prefix + "10000-12000\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AppSettings.server(file)).getMessage().contains("冲突"));
        Files.writeString(file, prefix + "12000-10000\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AppSettings.server(file)).getMessage().contains("起始端口"));
        Files.writeString(file, prefix + "11000-11003,11002\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AppSettings.server(file)).getMessage().contains("重复"));
        Files.writeString(file, prefix + "11000-16000\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AppSettings.server(file)).getMessage().contains("4096"));
    }

    @Test
    void compactedRangesRoundTripThroughConfigurationFile() throws Exception {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "mode=simple\nhandshake.port=9000\nadmin.port=19090\nforwards=\n");
        ForwardFile.save(file, PortRanges.compact(List.of(10000, 10001, 10002, 12000, 65535)));
        assertTrue(Files.readString(file).contains("forwards=10000-10003,12000,65535"));
        assertEquals(List.of(10000, 10001, 10002, 12000, 65535), AppSettings.server(file).forwards());
    }

    @Test
    void loadsKeysFromBothFilesAndPreservesThemWhenPortsChange() throws Exception {
        String aes = Base64.getEncoder().encodeToString(new byte[32]);
        String token = "t".repeat(32);
        Path server = dir.resolve("server.properties");
        Path client = dir.resolve("client.properties");
        Files.writeString(server, "mode=secretAll\nAES_KEY=" + aes + "\nTOKEN_KEY=" + token
                + "\nhandshake.port=10010\nadmin.port=19090\nforwards=18080\n");
        Files.writeString(client, "mode=secretAll\nAES_KEY=" + aes + "\nTOKEN_KEY=" + token
                + "\nserver.host=localhost\nhandshake.port=10010\nadmin.port=19091\nforwards=18080:8080\n");

        AppSettings.Server serverSettings = AppSettings.server(server);
        AppSettings.Client clientSettings = AppSettings.client(client);
        assertEquals(serverSettings.keys(), clientSettings.keys());
        assertDoesNotThrow(() -> SecurityConfig.validate(serverSettings.mode(), serverSettings.keys()));
        assertFalse(serverSettings.toString().contains(aes));
        assertFalse(serverSettings.toString().contains(token));

        ForwardFile.save(server, List.of("18080-18082"));
        assertEquals(serverSettings.keys(), AppSettings.server(server).keys());
    }

    @Test
    void missingOrInvalidKeysProduceClearErrors() {
        assertDoesNotThrow(() -> SecurityConfig.validate(TunnelMode.SIMPLE, new SecurityConfig.Keys("", "")));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validate(TunnelMode.SECRET_ALL, new SecurityConfig.Keys("", "")))
                .getMessage().contains("AES_KEY"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validate(TunnelMode.SECRET_ALL, new SecurityConfig.Keys("abcd", "x")))
                .getMessage().contains("TOKEN_KEY"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validate(TunnelMode.SECRET_ALL, new SecurityConfig.Keys("invalid!", "x".repeat(32))))
                .getMessage().contains("Base64"));
    }
}
