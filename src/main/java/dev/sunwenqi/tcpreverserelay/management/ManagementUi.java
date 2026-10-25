package dev.sunwenqi.tcpreverserelay.management;

import com.alibaba.fastjson.JSON;
import com.sun.net.httpserver.*;
import dev.sunwenqi.tcpreverserelay.config.*;
import dev.sunwenqi.tcpreverserelay.forward.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** HTTP management for local or explicitly enabled remote access. */
public final class ManagementUi implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private volatile String token;
    private volatile boolean publicAccess;
    private final Path configFile;
    private final int port;
    private final ForwardManager forwards;
    private final boolean client;
    private final boolean externalPassword;

    public ManagementUi(int port, boolean client, ForwardManager forwards) throws IOException {
        this(port, client, forwards, System.getenv("TCP_REVERSE_RELAY_ADMIN_TOKEN"));
    }
    public ManagementUi(int port, boolean client, ForwardManager forwards, String token) throws IOException {
        this(port, client, forwards, null, token, false);
    }
    public ManagementUi(int port, boolean client, ForwardManager forwards, Path configFile) throws IOException {
        this(port, client, forwards, configFile, AdminTokenFile.resolve(configFile), AdminSettingsFile.publicAccess(configFile));
    }
    private ManagementUi(int port, boolean client, ForwardManager forwards, Path configFile, String token, boolean publicAccess) throws IOException {
        this.token = token == null ? "" : token;
        if (!this.token.isEmpty() && (this.token.length() < 8 || this.token.length() > 128))
            throw new IllegalStateException("ADMIN_TOKEN 需为 8 至 128 个字符；留空则在首次访问时设置");
        this.port = port; this.client = client; this.forwards = forwards; this.configFile = configFile;
        this.publicAccess = !this.token.isEmpty() && publicAccess;
        String environment = System.getenv("TCP_REVERSE_RELAY_ADMIN_TOKEN");
        externalPassword = environment != null && !environment.isBlank();
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 16);
        server.createContext("/", this::handle);
        executor = Executors.newFixedThreadPool(4, task -> { Thread t = new Thread(task, "relay-admin"); t.setDaemon(true); return t; });
        server.setExecutor(executor);
    }
    public void start() { server.start(); System.out.println("管理页: http://127.0.0.1:" + port + "/（外网访问" + (publicAccess ? "已开启" : "已关闭") + "）"); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            boolean local = exchange.getRemoteAddress().getAddress().isLoopbackAddress();
            if (!local && (!publicAccess || token.isEmpty())) { reply(exchange, 403, "管理页外网访问已关闭"); return; }
            String host = exchange.getRequestHeaders().getFirst("Host");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            boolean localHost = ("127.0.0.1:" + port).equals(host) || ("localhost:" + port).equals(host) || ("[::1]:" + port).equals(host);
            if (host == null || (!localHost && !(publicAccess && host.endsWith(":" + port)))) { reply(exchange, 403, "无效 Host"); return; }
            if (origin != null && !origin.equals("http://" + host)) { reply(exchange, 403, "无效 Origin"); return; }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'self'");
            if (exchange.getRequestURI().getRawQuery() != null) { reply(exchange, 400, "URL 不支持查询参数"); return; }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/management/style.css") || path.equals("/management/app.js")) {
                requireMethod(exchange, "GET");
                String resource = path.equals("/") ? "/management/index.html" : path;
                String body = resource(resource).replace("{{ROLE}}", client ? "客户端" : "服务端")
                        .replace("{{MODE}}", client ? "client" : "server").replace("{{SETUP}}", token.isEmpty() ? "true" : "false");
                exchange.getResponseHeaders().set("Content-Type", path.endsWith(".css") ? "text/css; charset=UTF-8"
                        : path.endsWith(".js") ? "text/javascript; charset=UTF-8" : "text/html; charset=UTF-8");
                reply(exchange, 200, body); return;
            }
            if (path.equals("/api/setup")) {
                requireMethod(exchange, "POST");
                if (!local) { reply(exchange, 403, "首次设置密码只能在本机进行"); return; }
                Map<String, String> fields = form(exchange, 4096); exact(fields, "password");
                synchronized (this) {
                    if (!token.isEmpty()) { reply(exchange, 409, "管理密码已经设置"); return; }
                    requireConfig();
                    AdminSettingsFile.setPublicAccess(configFile, false);
                    AdminSettingsFile.setPassword(configFile, fields.get("password"));
                    token = fields.get("password"); publicAccess = false;
                }
                reply(exchange, 200, "管理密码已设置"); return;
            }
            if (!Set.of("/api/forwards", "/api/forwards/delete", "/api/forwards/batch", "/api/forwards/batch/preview",
                    "/api/forwards/diagnose", "/api/clients", "/api/settings", "/api/password").contains(path)) {
                reply(exchange, 404, "页面不存在"); return;
            }
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (token.isEmpty() || !equal("Bearer " + token, supplied)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer"); reply(exchange, 401, "管理密码不正确或已经失效"); return;
            }
            if (path.equals("/api/forwards") && exchange.getRequestMethod().equals("GET")) { json(exchange, state()); return; }
            if (path.equals("/api/clients")) {
                requireMethod(exchange, "GET");
                if (client) { reply(exchange, 404, "客户端清单仅在服务端提供"); return; }
                var clients = new ArrayList<Map<String, Object>>();
                for (var item : forwards.clients()) {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("id", item.id()); value.put("name", item.name()); value.put("address", item.address()); value.put("version", item.version());
                    value.put("online", item.online()); value.put("connectedAt", item.connectedAt()); value.put("lastSeenAt", item.lastSeenAt());
                    value.put("mappings", item.mappings().stream().map(ManagementUi::status).toList()); clients.add(value);
                }
                json(exchange, Map.of("clients", clients, "note", "清单保留本次服务运行期间的客户端记录；重启后由客户端重新注册恢复。")); return;
            }
            if (path.equals("/api/settings") && exchange.getRequestMethod().equals("GET")) {
                json(exchange, Map.of("publicAccess", publicAccess, "canChangePassword", configFile != null && !externalPassword)); return;
            }
            requireMethod(exchange, "POST");
            Map<String, String> fields = form(exchange, path.contains("/batch") ? 262144 : 4096);
            switch (path) {
                case "/api/forwards" -> {
                    allowed(fields, client ? Set.of("oldPort", "port", "target", "targetHost") : Set.of("oldPort", "port"));
                    Integer old = fields.getOrDefault("oldPort", "").isBlank() ? null : AppSettings.parsePort(fields.get("oldPort"), "oldPort");
                    forwards.upsert(old, AppSettings.parsePort(fields.get("port"), "port"), client ? fields.getOrDefault("targetHost", "127.0.0.1") : null,
                            client ? AppSettings.parsePort(fields.get("target"), "target") : null);
                    json(exchange, mutationState());
                }
                case "/api/forwards/delete" -> {
                    exact(fields, "port"); forwards.delete(AppSettings.parsePort(fields.get("port"), "port")); json(exchange, mutationState());
                }
                case "/api/forwards/batch", "/api/forwards/batch/preview" -> {
                    exact(fields, "action", "entries");
                    var result = forwards.batch(fields.get("action"), fields.get("entries"), path.endsWith("/preview"));
                    json(exchange, Map.of("action", result.action(), "preview", result.preview(), "ports", result.ports(), "count", result.ports().size(),
                            "connections", result.connections(), "message", result.message()));
                }
                case "/api/forwards/diagnose" -> {
                    exact(fields, "port"); var result = forwards.diagnose(AppSettings.parsePort(fields.get("port"), "port"));
                    json(exchange, Map.of("port", result.port(), "checkedAt", result.checkedAt(), "note", result.note(),
                            "checks", result.checks().stream().map(check -> Map.of("name", check.name(), "status", check.status(),
                                    "detail", check.detail(), "durationMs", check.durationMs())).toList()));
                }
                case "/api/settings" -> {
                    exact(fields, "public"); requireConfig();
                    if (!Set.of("true", "false").contains(fields.get("public"))) throw new IllegalArgumentException("public 只能是 true 或 false");
                    boolean enabled = Boolean.parseBoolean(fields.get("public"));
                    AdminSettingsFile.setPublicAccess(configFile, enabled); publicAccess = enabled;
                    json(exchange, Map.of("message", enabled ? "外网访问已开启" : "外网访问已关闭"));
                }
                case "/api/password" -> {
                    exact(fields, "currentPassword", "newPassword"); requireConfig();
                    if (externalPassword) { reply(exchange, 409, "管理密码由环境变量提供，请修改环境变量并重启程序"); return; }
                    synchronized (this) {
                        if (!equal(token, fields.get("currentPassword"))) { reply(exchange, 401, "当前密码不正确"); return; }
                        AdminSettingsFile.changePassword(configFile, token, fields.get("newPassword")); token = fields.get("newPassword");
                    }
                    json(exchange, Map.of("message", "密码已修改，请重新登录"));
                }
                default -> throw new IllegalArgumentException("不支持的操作");
            }
        } catch (MethodNotAllowed e) { exchange.getResponseHeaders().set("Allow", e.method); reply(exchange, 405, "请求方法不支持");
        } catch (IllegalArgumentException e) { reply(exchange, 400, e.getMessage());
        } catch (IOException | IllegalStateException e) { reply(exchange, 409, e.getMessage());
        } catch (Exception e) { reply(exchange, 500, "操作失败，请查看程序日志");
        } finally { exchange.close(); }
    }

    private Map<String, Object> mutationState() {
        var state = state();
        long pending = forwards.list().stream().filter(item -> item.enabled() && !item.connected()).count();
        state.put("message", client ? (pending > 0 ? "配置已保存，" + pending + " 个映射等待服务端注册" : "配置已保存，已注册映射的新连接使用最新目标") : "端口配置已保存并应用");
        state.put("saved", true); state.put("pending", pending); return state;
    }
    private Map<String, Object> state() {
        var result = new LinkedHashMap<String, Object>();
        result.put("forwards", forwards.list().stream().map(ManagementUi::status).toList());
        result.put("serverTime", System.currentTimeMillis()); result.put("role", client ? "client" : "server"); return result;
    }
    private static Map<String, Object> status(ForwardStatus item) {
        var result = new LinkedHashMap<String, Object>();
        result.put("port", item.port()); result.put("target", item.target()); result.put("targetHost", item.targetHost());
        result.put("connected", item.connected()); result.put("connections", item.connections()); result.put("enabled", item.enabled());
        result.put("state", item.state()); result.put("lastError", item.lastError()); result.put("lastAttemptAt", item.lastAttemptAt());
        result.put("nextRetryAt", item.nextRetryAt()); result.put("clientId", item.clientId()); result.put("clientName", item.clientName());
        result.put("clientAddress", item.clientAddress()); result.put("connectedAt", item.connectedAt()); result.put("lastSeenAt", item.lastSeenAt()); return result;
    }
    private static boolean equal(String expected, String supplied) {
        return supplied != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    private void requireConfig() { if (configFile == null) throw new IllegalStateException("管理页缺少可写配置文件"); }
    private static void exact(Map<String, String> values, String... names) {
        if (!values.keySet().equals(Set.of(names))) throw new IllegalArgumentException("此操作只接受: " + String.join(", ", names));
    }
    private static void allowed(Map<String, String> fields, Set<String> allowed) {
        for (String key : fields.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("未知表单字段: " + key);
    }
    private static Map<String, String> form(HttpExchange exchange, int limit) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) throw new IllegalArgumentException("需要表单格式的请求内容");
        byte[] bytes = exchange.getRequestBody().readNBytes(limit + 1);
        if (bytes.length > limit) throw new IllegalArgumentException("请求内容过大");
        var fields = new HashMap<String, String>();
        for (String part : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) throw new IllegalArgumentException("无效表单");
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8), value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            if (fields.putIfAbsent(key, value) != null) throw new IllegalArgumentException("重复表单字段: " + key);
        }
        return fields;
    }
    private static String resource(String name) throws IOException {
        try (InputStream stream = ManagementUi.class.getResourceAsStream(name)) {
            if (stream == null) throw new IOException("程序缺少页面资源: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static void json(HttpExchange exchange, Object value) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8"); reply(exchange, 200, JSON.toJSONString(value));
    }
    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = (body == null ? "操作失败" : body).getBytes(StandardCharsets.UTF_8);
        if (!exchange.getResponseHeaders().containsKey("Content-Type")) exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private static void requireMethod(HttpExchange exchange, String method) {
        if (!exchange.getRequestMethod().equals(method)) throw new MethodNotAllowed(method);
    }
    private static final class MethodNotAllowed extends RuntimeException {
        final String method; MethodNotAllowed(String method) { this.method = method; }
    }
}
