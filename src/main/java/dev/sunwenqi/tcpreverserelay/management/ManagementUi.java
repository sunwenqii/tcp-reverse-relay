package dev.sunwenqi.tcpreverserelay.management;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sunwenqi.tcpreverserelay.config.AdminSettingsFile;
import dev.sunwenqi.tcpreverserelay.config.AdminTokenFile;
import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.forward.ForwardManager;
import dev.sunwenqi.tcpreverserelay.forward.ForwardStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Management page with a local-only first-password setup and a remote-access switch.
 */
public final class ManagementUi implements AutoCloseable {
    private final HttpServer server;
    private volatile String token;
    private volatile boolean publicAccess;
    private final Path configFile;
    private final int port;
    private final ForwardManager forwards;
    private final boolean client;

    public ManagementUi(int port, boolean client, ForwardManager forwards) throws IOException {
        this(port, client, forwards, System.getenv("TCP_REVERSE_RELAY_ADMIN_TOKEN"));
    }

    public ManagementUi(int port, boolean client, ForwardManager forwards, String adminToken) throws IOException {
        this(port, client, forwards, null, adminToken, false);
    }

    public ManagementUi(int port, boolean client, ForwardManager forwards, Path configFile) throws IOException {
        this(port, client, forwards, configFile, AdminTokenFile.resolve(configFile),
                AdminSettingsFile.publicAccess(configFile));
    }

    private ManagementUi(int port, boolean client, ForwardManager forwards, Path configFile,
                         String adminToken, boolean publicAccess) throws IOException {
        token = adminToken;
        if (token == null) token = "";
        if (!token.isEmpty() && (token.length() < 8 || token.length() > 128)) {
            throw new IllegalStateException("ADMIN_TOKEN 需为 8 至 128 个字符；留空则在首次访问时设置");
        }
        this.configFile = configFile;
        this.publicAccess = !token.isEmpty() && publicAccess;
        this.port = port;
        this.client = client;
        this.forwards = forwards;
        // Keep the socket open so the access switch takes effect without restarting.
        // Requests from other machines are rejected before any page or API is served while off.
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 16);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "relay-admin");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public void start() {
        server.start();
        System.out.println("管理页: http://127.0.0.1:" + port + "/（外网访问" + (publicAccess ? "已开启" : "已关闭") + "）");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            boolean localRequest = exchange.getRemoteAddress().getAddress().isLoopbackAddress();
            if (!localRequest && (!publicAccess || token.isEmpty())) {
                reply(exchange, 403, "管理页外网访问已关闭");
                return;
            }
            String host = exchange.getRequestHeaders().getFirst("Host");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            boolean localHost = ("127.0.0.1:" + port).equals(host) || ("localhost:" + port).equals(host)
                    || ("[::1]:" + port).equals(host);
            if (host == null || (!localHost && !(publicAccess && host.endsWith(":" + port)))) {
                reply(exchange, 403, "无效 Host");
                return;
            }
            if (origin != null && !origin.equals("http://" + host)) {
                reply(exchange, 403, "无效 Origin");
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'self'");
            if (exchange.getRequestURI().getRawQuery() != null) {
                reply(exchange, 400, "URL 不支持查询参数");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/") && method.equals("GET")) {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                reply(exchange, 200, resource("/management/index.html")
                        .replace("{{ROLE}}", client ? "客户端" : "服务端")
                        .replace("{{MODE}}", client ? "client" : "server")
                        .replace("{{SETUP}}", token.isEmpty() ? "true" : "false"));
                return;
            }
            if (path.equals("/management/style.css") && method.equals("GET")) {
                exchange.getResponseHeaders().set("Content-Type", "text/css; charset=UTF-8");
                reply(exchange, 200, resource("/management/style.css"));
                return;
            }
            if (path.equals("/management/app.js") && method.equals("GET")) {
                exchange.getResponseHeaders().set("Content-Type", "text/javascript; charset=UTF-8");
                reply(exchange, 200, resource("/management/app.js"));
                return;
            }
            if (path.equals("/api/setup")) {
                if (!localRequest) {
                    reply(exchange, 403, "首次设置密码只能在本机进行");
                    return;
                }
                if (!method.equals("POST")) {
                    reply(exchange, 405, "请求方法不支持");
                    return;
                }
                Map<String, String> setup = formFields(exchange);
                if (!setup.keySet().equals(java.util.Set.of("password"))) {
                    throw new IllegalArgumentException("设置密码只接受 password");
                }
                synchronized (this) {
                    if (!token.isEmpty()) {
                        reply(exchange, 409, "管理密码已经设置");
                        return;
                    }
                    if (configFile == null) {
                        reply(exchange, 409, "管理页缺少配置文件");
                        return;
                    }
                    AdminSettingsFile.setPublicAccess(configFile, false);
                    AdminSettingsFile.setPassword(configFile, setup.get("password"));
                    token = setup.get("password");
                    publicAccess = false;
                }
                reply(exchange, 200, "管理密码已设置");
                return;
            }
            if (!path.equals("/api/forwards") && !path.equals("/api/forwards/delete")
                    && !path.equals("/api/settings")) {
                reply(exchange, 404, "页面不存在");
                return;
            }
            if (token.isEmpty()) {
                reply(exchange, 401, "请先在本机设置管理密码");
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            byte[] supplied = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, supplied)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                reply(exchange, 401, "管理令牌错误");
                return;
            }
            if (path.equals("/api/forwards") && method.equals("GET")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                reply(exchange, 200, stateJson());
                return;
            }
            if (path.equals("/api/settings")) {
                if (method.equals("GET")) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    reply(exchange, 200, "{\"publicAccess\":" + publicAccess + "}");
                    return;
                }
                Map<String, String> setting = formFields(exchange);
                if (!setting.keySet().equals(java.util.Set.of("public"))
                        || !(setting.get("public").equals("true") || setting.get("public").equals("false"))) {
                    throw new IllegalArgumentException("public 只能是 true 或 false");
                }
                if (configFile == null) {
                    reply(exchange, 409, "管理页缺少配置文件");
                    return;
                }
                boolean enabled = Boolean.parseBoolean(setting.get("public"));
                AdminSettingsFile.setPublicAccess(configFile, enabled);
                publicAccess = enabled;
                reply(exchange, 200, enabled ? "外网访问已开启" : "外网访问已关闭");
                return;
            }
            Map<String, String> fields = formFields(exchange);
            if (path.endsWith("/delete")) {
                if (!fields.keySet().equals(java.util.Set.of("port")))
                    throw new IllegalArgumentException("删除操作只接受 port");
                forwards.delete(AppSettings.parsePort(fields.get("port"), "port"));
            } else {
                for (String key : fields.keySet()) {
                    if (!key.equals("oldPort") && !key.equals("port") && !(client && key.equals("target"))) {
                        throw new IllegalArgumentException("未知表单字段: " + key);
                    }
                }
                Integer oldPort = fields.containsKey("oldPort") && !fields.get("oldPort").isBlank()
                        ? AppSettings.parsePort(fields.get("oldPort"), "oldPort") : null;
                int newPort = AppSettings.parsePort(fields.get("port"), "port");
                Integer target = client ? AppSettings.parsePort(fields.get("target"), "target") : null;
                forwards.upsert(oldPort, newPort, target);
            }
            reply(exchange, 200, "已保存并应用");
        } catch (IllegalArgumentException e) {
            reply(exchange, 400, e.getMessage());
        } catch (IOException e) {
            reply(exchange, 409, e.getMessage());
        } catch (Exception e) {
            reply(exchange, 500, "操作失败，请查看程序日志");
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> fields(String body) {
        Map<String, String> result = new HashMap<>();
        for (String part : body.split("&", -1)) {
            if (part.isBlank()) continue;
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("重复表单字段: " + key);
        }
        return result;
    }

    private static Map<String, String> formFields(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            throw new IllegalArgumentException("请求方法只支持 POST");
        }
        if (!"application/x-www-form-urlencoded".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
            throw new IllegalArgumentException("请求格式应为表单");
        }
        byte[] data = exchange.getRequestBody().readNBytes(2049);
        if (data.length > 2048) throw new IllegalArgumentException("请求内容过大");
        return fields(new String(data, StandardCharsets.UTF_8));
    }

    private String stateJson() {
        StringBuilder result = new StringBuilder("{\"forwards\":[");
        boolean first = true;
        for (ForwardStatus status : forwards.list()) {
            if (!first) result.append(',');
            first = false;
            result.append("{\"port\":").append(status.port())
                    .append(",\"connected\":").append(status.connected());
            if (status.target() != null) {
                result.append(",\"target\":").append(status.target());
            }
            if (!client) {
                result.append(",\"connections\":").append(status.connections());
            }
            result.append('}');
        }
        return result.append("]}").toString();
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        }
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = ManagementUi.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("管理页资源缺失: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
