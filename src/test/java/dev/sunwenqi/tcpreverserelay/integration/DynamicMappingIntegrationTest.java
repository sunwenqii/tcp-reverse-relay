package dev.sunwenqi.tcpreverserelay.integration;

import dev.sunwenqi.tcpreverserelay.clientside.ClientRuntime;
import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.executor.RelayExecutor;
import dev.sunwenqi.tcpreverserelay.executor.SimpleExecutor;
import dev.sunwenqi.tcpreverserelay.nio.NioHallows;
import dev.sunwenqi.tcpreverserelay.serverside.ServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DynamicMappingIntegrationTest {
    @TempDir
    Path dir;

    @Test
    @Timeout(30)
    void clientRegistersAgainWhenServerPortReturns() throws Exception {
        ServerSocket target = new ServerSocket(0);
        int handshake;
        int admin;
        int forward;
        try (ServerSocket h = new ServerSocket(0);
             ServerSocket a = new ServerSocket(0);
             ServerSocket f = new ServerSocket(0)) {
            handshake = h.getLocalPort();
            admin = a.getLocalPort();
            forward = f.getLocalPort();
        }
        Path serverFile = dir.resolve("server.properties");
        Path clientFile = dir.resolve("client.properties");
        Files.writeString(serverFile, "mode=simple\nhandshake.port=" + handshake
                + "\nadmin.port=" + admin + "\nforwards=" + forward + "\n");
        Files.writeString(clientFile, "mode=simple\nserver.host=127.0.0.1\nhandshake.port=" + handshake
                + "\nadmin.port=" + admin + "\nforwards=" + forward + ":" + target.getLocalPort() + "\n");
        ServerRuntime server = new ServerRuntime(serverFile, AppSettings.server(serverFile));
        ClientRuntime client = new ClientRuntime(clientFile, AppSettings.client(clientFile));
        try {
            server.start();
            client.start();
            awaitState(client, true, Duration.ofSeconds(3));
            server.delete(forward);
            awaitState(client, false, Duration.ofSeconds(3));
            server.upsert(null, forward);
            awaitState(client, true, Duration.ofSeconds(18));
            Thread echo = new Thread(() -> {
                try (Socket local = target.accept()) {
                    local.getOutputStream().write(local.getInputStream().readNBytes(4));
                } catch (Exception ignored) {
                    // The assertion on the remote side reports a failed relay.
                }
            }, "test-target");
            echo.start();
            try (Socket remote = new Socket("127.0.0.1", forward)) {
                remote.setSoTimeout(5000);
                remote.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
                assertEquals("PING", new String(remote.getInputStream().readNBytes(4), StandardCharsets.US_ASCII));
            }
            client.upsert(forward, forward, 9090);
            assertEquals(9090, AppSettings.client(clientFile).forwards().get(0).localPort());
            client.delete(forward);
            assertTrue(AppSettings.client(clientFile).forwards().isEmpty());
            client.upsert(null, forward, target.getLocalPort());
            awaitState(client, true, Duration.ofSeconds(3));
            assertTrue(AppSettings.server(serverFile).forwards().contains(forward));
            assertTrue(AppSettings.client(clientFile).forwards().stream()
                    .anyMatch(mapping -> mapping.remotePort() == forward));
        } finally {
            target.close();
            client.close();
            server.close();
            NioHallows.INSTANCE.cancel();
            RelayExecutor.resetExecutor(new SimpleExecutor());
        }
    }

    private static void awaitState(ClientRuntime client, boolean connected, Duration timeout) throws Exception {
        long until = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < until) {
            if (client.list().stream().anyMatch(item -> item.connected() == connected)) return;
            Thread.sleep(100);
        }
        fail("客户端未在预期时间内切换连接状态: " + client.list());
    }

}
