package dev.sunwenqi.tcpreverserelay.integration;

import dev.sunwenqi.tcpreverserelay.clientside.ClientRuntime;
import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.executor.*;
import dev.sunwenqi.tcpreverserelay.nio.NioHallows;
import dev.sunwenqi.tcpreverserelay.serverside.ServerRuntime;
import dev.sunwenqi.tcpreverserelay.management.ManagementUi;
import com.alibaba.fastjson.JSON;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ManagementFeaturesIntegrationTest {
    @TempDir Path dir;
    @BeforeEach void startNetworkLoop() { NioHallows.INSTANCE.start(); }
    private static int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }

    @ParameterizedTest @ValueSource(strings = {"simple", "secretAll"}) @Timeout(40)
    void inventoryRemoteDiagnosisCustomTargetsAndPauseResume(String mode) throws Exception {
        int handshake = freePort(), admin = freePort(), port = freePort();
        String keys = "ADMIN_TOKEN=test-pass-123\nAES_KEY=" + Base64.getEncoder().encodeToString(new byte[32]) + "\nTOKEN_KEY=" + "x".repeat(32) + "\n";
        Path serverFile = dir.resolve("server.properties"), clientFile = dir.resolve("client.properties");
        try (var target = new ServerSocket(0)) {
            Thread echo = new Thread(() -> {
                while (!target.isClosed()) try (Socket socket = target.accept()) {
                    socket.setSoTimeout(3000);
                    byte[] data = socket.getInputStream().readNBytes(4);
                    if (data.length > 0) socket.getOutputStream().write(data);
                } catch (Exception ignored) {}
            }, "test-echo");
            echo.setDaemon(true); echo.start();
            Files.writeString(serverFile, "mode=" + mode + "\nhandshake.port=" + handshake + "\nadmin.port=" + admin + "\nforwards=" + port + "\n" + keys);
            Files.writeString(clientFile, "mode=" + mode + "\nserver.host=127.0.0.1\nhandshake.port=" + handshake + "\nadmin.port=" + admin
                    + "\nclient.id=test-client\nclient.name=Office PC\nforwards=" + port + ":localhost:" + target.getLocalPort() + "\n" + keys);
            var server = new ServerRuntime(serverFile, AppSettings.server(serverFile));
            var client = new ClientRuntime(clientFile, AppSettings.client(clientFile));
            try {
                server.start(); client.start();
                await(() -> client.list().get(0).connected() && server.list().get(0).connected(), 5000);
                var info = server.clients().get(0);
                assertEquals("test-client", info.id()); assertEquals("Office PC", info.name());
                assertEquals("localhost", info.mappings().get(0).targetHost());
                assertEquals("1.1.0", info.version());
                try (var ui = new ManagementUi(admin, false, server, serverFile)) {
                    ui.start();
                    var http = HttpClient.newHttpClient();
                    URI home = URI.create("http://127.0.0.1:" + admin);
                    var directory = http.send(HttpRequest.newBuilder(home.resolve("/api/clients"))
                            .header("Authorization", "Bearer test-pass-123").GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, directory.statusCode());
                    assertEquals("test-client", JSON.parseObject(directory.body()).getJSONArray("clients").getJSONObject(0).getString("id"));
                    var diagnostic = http.send(HttpRequest.newBuilder(home.resolve("/api/forwards/diagnose"))
                            .header("Authorization", "Bearer test-pass-123").header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("port=" + port)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, diagnostic.statusCode());
                    assertEquals("passed", JSON.parseObject(diagnostic.body()).getJSONArray("checks").getJSONObject(2).getString("status"));
                }
                assertEquals("passed", client.diagnose(port).checks().get(2).status());
                try (Socket remote = new Socket("127.0.0.1", port)) {
                    remote.setSoTimeout(4000); remote.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
                    assertEquals("PING", new String(remote.getInputStream().readNBytes(4), StandardCharsets.US_ASCII));
                }
                client.upsert(port, port, "127.0.0.1", target.getLocalPort());
                await(() -> "127.0.0.1".equals(server.list().get(0).targetHost()), 3000);
                client.batch("disable", Integer.toString(port), false);
                assertEquals("paused", client.list().get(0).state());
                assertTrue(AppSettings.client(clientFile).disabledForwards().contains(port));
                await(() -> !server.list().get(0).connected(), 3000);
                client.batch("enable", Integer.toString(port), false);
                await(() -> client.list().get(0).connected() && server.list().get(0).connected(), 5000);
                assertEquals(1, server.clients().size());
                target.close();
                assertEquals("failed", server.diagnose(port).checks().get(2).status());
                assertTrue(server.list().get(0).connected(), "目标服务失败不能伪装成控制连接离线");
                server.batch("disable", Integer.toString(port), false);
                assertEquals("paused", server.list().get(0).state());
                assertTrue(AppSettings.server(serverFile).disabledForwards().contains(port));
                assertFalse(server.clients().get(0).online());
                server.batch("enable", Integer.toString(port), false);
                await(() -> server.list().get(0).connected(), 15000);
            } finally {
                client.close(); server.close(); NioHallows.INSTANCE.cancel(); RelayExecutor.resetExecutor(new SimpleExecutor());
            }
        }
    }

    @Test @Timeout(15) void occupiedPortRollsBackWholeServerBatch() throws Exception {
        int handshake = freePort(), admin = freePort(), existing = freePort(), candidate = freePort();
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "mode=simple\nhandshake.port=" + handshake + "\nadmin.port=" + admin + "\nforwards=" + existing + "\n");
        var server = new ServerRuntime(file, AppSettings.server(file));
        try (var occupied = new ServerSocket(0)) {
            server.start(); String original = Files.readString(file);
            String entries = candidate + "," + occupied.getLocalPort();
            assertEquals(2, server.batch("add", entries, true).ports().size());
            assertThrows(java.io.IOException.class, () -> server.batch("add", entries, false));
            assertEquals(original, Files.readString(file)); assertEquals(1, server.list().size());
            try (var released = new ServerSocket(candidate)) { assertEquals(candidate, released.getLocalPort()); }
        } finally { server.close(); NioHallows.INSTANCE.cancel(); RelayExecutor.resetExecutor(new SimpleExecutor()); }
    }

    @Test @Timeout(15) void apiDistinguishesSavedConfigurationFromRegistrationFailure() throws Exception {
        int handshake = freePort(), serverAdmin = freePort(), clientAdmin = freePort(), port = freePort(), target = freePort();
        Path serverFile = dir.resolve("server.properties"), clientFile = dir.resolve("client.properties");
        Files.writeString(serverFile, "mode=simple\nhandshake.port=" + handshake + "\nadmin.port=" + serverAdmin + "\nforwards=\n");
        Files.writeString(clientFile, "mode=simple\nserver.host=127.0.0.1\nhandshake.port=" + handshake
                + "\nadmin.port=" + clientAdmin + "\nADMIN_TOKEN=test-pass-123\nforwards=\n");
        var server = new ServerRuntime(serverFile, AppSettings.server(serverFile));
        var client = new ClientRuntime(clientFile, AppSettings.client(clientFile));
        try (var ui = new ManagementUi(clientAdmin, true, client, clientFile)) {
            server.start(); client.start(); ui.start();
            var http = HttpClient.newHttpClient(); URI home = URI.create("http://127.0.0.1:" + clientAdmin);
            var response = http.send(HttpRequest.newBuilder(home.resolve("/api/forwards"))
                    .header("Authorization", "Bearer test-pass-123").header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("port=" + port + "&targetHost=localhost&target=" + target)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var body = JSON.parseObject(response.body());
            assertTrue(body.getBooleanValue("saved")); assertEquals(1, body.getIntValue("pending"));
            await(() -> "error".equals(client.list().get(0).state()), 4000);
            assertFalse(client.list().get(0).lastError().isBlank());
            assertEquals("localhost", AppSettings.client(clientFile).forwards().get(0).targetHost());
            assertEquals(401, http.send(HttpRequest.newBuilder(home.resolve("/api/forwards/batch"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("action=delete&entries=" + port)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            String original = Files.readString(clientFile);
            var rejected = http.send(HttpRequest.newBuilder(home.resolve("/api/forwards/batch"))
                    .header("Authorization", "Bearer test-pass-123").header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("action=delete&entries=" + port + "," + (port + 1))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, rejected.statusCode()); assertEquals(original, Files.readString(clientFile));
        } finally { client.close(); server.close(); NioHallows.INSTANCE.cancel(); RelayExecutor.resetExecutor(new SimpleExecutor()); }
    }

    private static void await(BooleanSupplier predicate, int timeout) throws Exception {
        long until = System.nanoTime() + timeout * 1_000_000L;
        while (System.nanoTime() < until) { if (predicate.getAsBoolean()) return; Thread.sleep(50); }
        fail("未在预期时间内获得运行状态");
    }
}
