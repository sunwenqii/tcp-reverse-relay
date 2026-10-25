package dev.sunwenqi.tcpreverserelay.management;

import dev.sunwenqi.tcpreverserelay.forward.*;
import dev.sunwenqi.tcpreverserelay.config.AdminTokenFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManagementPasswordTest {
    @TempDir Path dir;
    @Test void changingPasswordInvalidatesOldTokenAndPersistsAcrossRestart() throws Exception {
        Path config = dir.resolve("server.properties");
        Files.writeString(config, "ADMIN_TOKEN=old-pass-123\nadmin.public=false\nforwards=\n");
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var manager = new ForwardManager() {
            public List<ForwardStatus> list() { return List.of(); }
            public void upsert(Integer old, int port, Integer target) {}
            public void delete(int port) {}
        };
        HttpClient http = HttpClient.newHttpClient(); URI home = URI.create("http://127.0.0.1:" + port);
        try (var ui = new ManagementUi(port, false, manager, config)) {
            ui.start();
            assertEquals(401, post(http, home, "old-pass-123", "currentPassword=wrong-pass&newPassword=new-pass-123"));
            assertEquals(400, post(http, home, "old-pass-123", "currentPassword=old-pass-123&newPassword=short"));
            assertEquals(200, post(http, home, "old-pass-123", "currentPassword=old-pass-123&newPassword=new-pass-123"));
            assertEquals(401, get(http, home, "old-pass-123"));
            assertEquals(200, get(http, home, "new-pass-123"));
            assertEquals("new-pass-123", AdminTokenFile.readInFile(config));
        }
        try (var ui = new ManagementUi(port, false, manager, config)) { ui.start(); assertEquals(200, get(http, home, "new-pass-123")); }
    }
    private int post(HttpClient http, URI home, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(home.resolve("/api/password")).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
    private int get(HttpClient http, URI home, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(home.resolve("/api/forwards")).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
