package dev.sunwenqi.tcpreverserelay.management;

import dev.sunwenqi.tcpreverserelay.forward.ForwardManager;
import dev.sunwenqi.tcpreverserelay.forward.ForwardStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ManagementUiTest {
    @TempDir
    Path directory;

    @Test
    void firstVisitSetsPasswordAndSwitchesRemoteAccess() throws Exception {
        Path config = directory.resolve("server.properties");
        Files.writeString(config, "ADMIN_TOKEN=\nadmin.public=false\n");
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        ForwardManager forwards = new ForwardManager() {
            public java.util.List<ForwardStatus> list() {
                return java.util.List.of();
            }

            public void upsert(Integer oldPort, int newPort, Integer target) {
            }

            public void delete(int remotePort) {
            }
        };
        HttpClient http = HttpClient.newHttpClient();
        URI home = URI.create("http://127.0.0.1:" + port + "/");
        try (ManagementUi ui = new ManagementUi(port, false, forwards, config)) {
            ui.start();
            assertTrue(http.send(HttpRequest.newBuilder(home).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .body().contains("data-setup=\"true\""));
            assertEquals(401, http.send(HttpRequest.newBuilder(home.resolve("/api/forwards"))
                    .header("Authorization", "Bearer ").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            URI setup = home.resolve("/api/setup");
            assertEquals(400, http.send(form(setup, "password=short"), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, http.send(form(setup, "password=good-pass-8"), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("good-pass-8", dev.sunwenqi.tcpreverserelay.config.AdminTokenFile.readInFile(config));
            assertEquals(409, http.send(form(setup, "password=another-pass"), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(http.send(HttpRequest.newBuilder(home).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .body().contains("data-setup=\"false\""));
            URI settings = home.resolve("/api/settings");
            assertEquals(401, http.send(HttpRequest.newBuilder(settings).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, http.send(HttpRequest.newBuilder(settings).header("Authorization", "Bearer good-pass-8")
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, http.send(HttpRequest.newBuilder(settings).header("Authorization", "Bearer good-pass-8")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("public=true")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(dev.sunwenqi.tcpreverserelay.config.AdminSettingsFile.publicAccess(config));
        }
    }

    private static HttpRequest form(URI uri, String body) {
        return HttpRequest.newBuilder(uri).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    @Test
    void rendersClientManagementPage() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        ForwardManager forwards = new ForwardManager() {
            public java.util.List<ForwardStatus> list() {
                return java.util.List.of(new ForwardStatus(18080, 8080, false, 0));
            }

            public void upsert(Integer oldPort, int newPort, Integer target) {
            }

            public void delete(int remotePort) {
            }
        };
        try (ManagementUi ui = new ManagementUi(port, true, forwards, "12345678901234567890123456789012")) {
            ui.start();
            HttpResponse<String> page = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("data-mode=\"client\""));
            assertTrue(page.body().contains("客户端管理页"));
        }
    }

    @Test
    void requiresTokenAndRejectsCrossOriginWrites() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        String token = "12345678901234567890123456789012";
        ForwardManager forwards = new ForwardManager() {
            public java.util.List<ForwardStatus> list() {
                return java.util.List.of(new ForwardStatus(18080, null, true, 2));
            }

            public void upsert(Integer oldPort, int newPort, Integer target) {
                fail("cross-origin mutation must be denied");
            }

            public void delete(int remotePort) {
                fail("cross-origin mutation must be denied");
            }
        };
        HttpClient client = HttpClient.newHttpClient();
        try (ManagementUi ui = new ManagementUi(port, false, forwards, token)) {
            ui.start();
            URI home = URI.create("http://127.0.0.1:" + port + "/");
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(home).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("data-mode=\"server\""));
            assertTrue(page.body().contains("端口映射"));
            assertEquals(200, client.send(HttpRequest.newBuilder(home.resolve("/management/style.css")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(home.resolve("/management/app.js")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/forwards");
            HttpRequest unauthenticated = HttpRequest.newBuilder(uri).GET().build();
            assertEquals(401, client.send(unauthenticated, HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpRequest authenticated = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> state = client.send(authenticated, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, state.statusCode());
            assertTrue(state.body().contains("\"port\":18080"));
            assertTrue(state.body().contains("\"connections\":2"));
            HttpRequest forged = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                    .header("Origin", "http://attacker.example")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("port=18080")).build();
            assertEquals(403, client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
