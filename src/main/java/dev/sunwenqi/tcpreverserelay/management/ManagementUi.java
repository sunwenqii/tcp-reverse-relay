package dev.sunwenqi.tcpreverserelay.management;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

import dev.sunwenqi.tcpreverserelay.config.*;
import dev.sunwenqi.tcpreverserelay.forward.*;
import dev.sunwenqi.tcpreverserelay.health.HealthSettings;
import dev.sunwenqi.tcpreverserelay.observability.EventJournal;

import com.alibaba.fastjson.JSON;
import com.sun.net.httpserver.*;

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
    private final EventJournal journal;

    public ManagementUi(int port, boolean client, ForwardManager forwards) throws IOException {
        this(port, client, forwards, System.getenv("TCP_REVERSE_RELAY_ADMIN_TOKEN"));
    }
    public ManagementUi(int port, boolean client, ForwardManager forwards, String token) throws IOException {
        this(port, client, forwards, null, token, false);
    }
    public ManagementUi(int port, boolean client, ForwardManager forwards, Path configFile) throws IOException {
        this(port, client, forwards, configFile, AdminTokenFile.resolve(configFile), AdminSettingsFile.publicAccess(configFile));
    }
    public ManagementUi(int port, boolean client, ForwardManager forwards, Path configFile, EventJournal journal) throws IOException {
        this(port, client, forwards, configFile, AdminTokenFile.resolve(configFile), AdminSettingsFile.publicAccess(configFile), journal);
    }
    private ManagementUi(int port, boolean client, ForwardManager forwards, Path configFile, String token, boolean publicAccess) throws IOException {
        this(port, client, forwards, configFile, token, publicAccess, null);
    }
    private ManagementUi(int port, boolean client, ForwardManager forwards, Path configFile, String token, boolean publicAccess, EventJournal journal) throws IOException {
        this.journal = journal;
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
        Integer operationPort = null;
        boolean operationCompleted = false;
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
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestURI().getRawQuery() != null && !path.equals("/api/events")) { reply(exchange, 400, "URL 不支持查询参数"); return; }
            if (path.equals("/") || path.equals("/management/style.css") || path.equals("/management/app.js") || path.equals("/management/journal.js")) {
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
                    operationCompleted = true;
                    if (journal != null) journal.addSecret(token);
                    record("INFO", "密码设置", null, "首次设置管理密码", "");
                }
                reply(exchange, 200, "管理密码已设置"); return;
            }
            if (!Set.of("/api/forwards", "/api/forwards/delete", "/api/forwards/batch", "/api/forwards/batch/preview",
                    "/api/connection/settings", "/api/forwards/owner", "/api/health/settings", "/api/forwards/diagnose", "/api/clients", "/api/settings", "/api/password", "/api/events").contains(path)) {
                reply(exchange, 404, "页面不存在"); return;
            }
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (token.isEmpty() || !equal("Bearer " + token, supplied)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer"); reply(exchange, 401, "管理密码不正确或已经失效"); return;
            }
            if (path.equals("/api/forwards") && exchange.getRequestMethod().equals("GET")) { json(exchange, state()); return; }
            if (path.equals("/api/events")) {
                requireMethod(exchange, "GET");
                if (journal == null) { reply(exchange, 503, "日志记录尚未初始化，请使用程序入口启动管理服务"); return; }
                String raw = exchange.getRequestURI().getRawQuery();
                if (raw != null && raw.length() > 4096) throw new IllegalArgumentException("筛选条件过长");
                var filters = raw == null || raw.isEmpty() ? new HashMap<String, String>() : decodeFields(raw);
                allowed(filters, Set.of("kind", "level", "port", "q", "from", "to", "before", "limit"));
                Integer selectedPort = filters.getOrDefault("port", "").isBlank() ? null : AppSettings.parsePort(filters.get("port"), "端口");
                long limit = number(filters, "limit", 50);
                if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit 需为 1 至 100");
                var filter = new EventJournal.Filter(filters.getOrDefault("kind", "event"), filters.getOrDefault("level", "all"), selectedPort,
                        filters.getOrDefault("q", ""), number(filters, "from", 0), number(filters, "to", 0), number(filters, "before", 0),
                        (int) limit);
                json(exchange, journal.query(filter)); return;
            }
            if (path.equals("/api/clients")) {
                requireMethod(exchange, "GET");
                if (client) { reply(exchange, 404, "客户端清单仅在服务端提供"); return; }
                var clients = new ArrayList<Map<String, Object>>();
                for (var item : forwards.clients()) {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("id", item.id()); value.put("name", item.name()); value.put("address", item.address()); value.put("version", item.version());
                    value.put("online", item.online()); value.put("connectedAt", item.connectedAt()); value.put("lastSeenAt", item.lastSeenAt());
                    value.put("mappings", item.mappings().stream().map(ForwardPayloads::status).toList()); clients.add(value);
                }
                json(exchange, Map.of("clients", clients, "note", "清单保留本次服务运行期间的客户端记录；重启后由客户端重新注册恢复。")); return;
            }
            if (path.equals("/api/settings") && exchange.getRequestMethod().equals("GET")) {
                json(exchange, Map.of("publicAccess", publicAccess, "canChangePassword", configFile != null && !externalPassword)); return;
            }
            if (path.equals("/api/connection/settings") && exchange.getRequestMethod().equals("GET")) {
                if (!client) { reply(exchange, 404, "仅客户端提供服务器连接设置"); return; }
                var connection = forwards.connectionStatus();
                if (connection == null) { reply(exchange, 409, "当前运行模块不支持服务器连接设置"); return; }
                json(exchange, ForwardPayloads.connection(connection)); return;
            }
            if (path.equals("/api/health/settings") && exchange.getRequestMethod().equals("GET")) {
                if (!client) { reply(exchange, 404, "目标检查在客户端设置"); return; }
                json(exchange, ForwardPayloads.healthSettings(forwards.healthSettings())); return;
            }
            requireMethod(exchange, "POST");
            Map<String, String> fields = form(exchange, path.contains("/batch") ? 262144 : 4096);
            switch (path) {
                case "/api/forwards" -> {
                    allowed(fields, client ? Set.of("oldPort", "port", "target", "targetHost") : Set.of("oldPort", "port"));
                    Integer old = fields.getOrDefault("oldPort", "").isBlank() ? null : AppSettings.parsePort(fields.get("oldPort"), "oldPort");
                    operationPort = AppSettings.parsePort(fields.get("port"), "port");
                    forwards.upsert(old, operationPort, client ? fields.getOrDefault("targetHost", "127.0.0.1") : null,
                            client ? AppSettings.parsePort(fields.get("target"), "target") : null);
                    operationCompleted = true;
                    record("INFO", "映射配置", operationPort, old == null ? "新增端口映射" : "修改端口映射",
                            (old == null ? "" : "原公网端口: " + old + "；") + (client ? "目标: " + fields.getOrDefault("targetHost", "127.0.0.1") + ":" + fields.get("target") : "服务端开放公网端口"));
                    json(exchange, mutationState());
                }
                case "/api/connection/settings" -> {
                    if (!client) { reply(exchange, 404, "仅客户端提供服务器连接设置"); return; }
                    exact(fields, "host", "port");
                    var connection = new ServerConnection(fields.get("host"), AppSettings.parsePort(fields.get("port"), "握手端口"));
                    boolean changed = forwards.configureConnection(connection); operationCompleted = true;
                    if (changed) record("INFO", "服务器连接", null, "服务器连接设置已修改", TargetAddress.display(connection.host(), connection.port()));
                    var result = state(); result.put("saved", true); result.put("changed", changed);
                    var status = forwards.connectionStatus();
                    result.put("message", !changed ? "连接配置已保存，服务器地址未变化" : status.enabledMappings() == 0
                            ? "连接配置已保存，新增或启用映射后将自动连接" : "连接配置已保存，启用的映射正在重新注册，请查看注册状态");
                    json(exchange, result);
                }
                case "/api/forwards/owner" -> {
                    if (client) { reply(exchange, 404, "端口归属在服务端设置"); return; }
                    exact(fields, "port", "clientId"); operationPort = AppSettings.parsePort(fields.get("port"), "port");
                    String owner = fields.get("clientId").trim();
                    forwards.assignOwner(operationPort, owner); operationCompleted = true;
                    record("INFO", "端口归属", operationPort, owner.isEmpty() ? "释放端口归属" : "调整端口归属", owner);
                    json(exchange, Map.of("message", owner.isEmpty() ? "归属已释放，下一次注册将自动绑定" : "归属已保存，等待指定客户端注册"));
                }
                case "/api/health/settings" -> {
                    if (!client) { reply(exchange, 404, "目标检查在客户端设置"); return; }
                    exact(fields, "enabled", "intervalSeconds", "timeoutMillis", "failureThreshold");
                    if (!Set.of("true", "false").contains(fields.get("enabled"))) throw new IllegalArgumentException("enabled 必须为 true 或 false");
                    var next = new HealthSettings(Boolean.parseBoolean(fields.get("enabled")),
                            Math.toIntExact(number(fields, "intervalSeconds", 15)), Math.toIntExact(number(fields, "timeoutMillis", 2000)),
                            Math.toIntExact(number(fields, "failureThreshold", 2)));
                    forwards.configureHealth(next); operationCompleted = true;
                    record("INFO", "目标检查", null, "目标连通性检查设置已保存", "");
                    json(exchange, ForwardPayloads.healthSettings(next));
                }
                case "/api/forwards/delete" -> {
                    exact(fields, "port"); operationPort = AppSettings.parsePort(fields.get("port"), "port");
                    forwards.delete(operationPort); operationCompleted = true;
                    record("INFO", "映射配置", operationPort, "删除端口映射", "该映射的现有连接已关闭"); json(exchange, mutationState());
                }
                case "/api/forwards/batch", "/api/forwards/batch/preview" -> {
                    exact(fields, "action", "entries");
                    var result = forwards.batch(fields.get("action"), fields.get("entries"), path.endsWith("/preview"));
                    operationCompleted = !result.preview();
                    if (!result.preview() && journal != null) {
                        String action = switch (result.action()) { case "add" -> "添加"; case "enable" -> "启用"; case "disable" -> "暂停"; default -> "删除"; };
                        journal.append("event", "INFO", "批量配置", result.ports().size() == 1 ? result.ports().get(0) : null, result.ports(),
                                action + " " + result.ports().size() + " 个映射", result.message(), "management");
                    }
                    json(exchange, Map.of("action", result.action(), "preview", result.preview(), "ports", result.ports(), "count", result.ports().size(),
                            "connections", result.connections(), "message", result.message()));
                }
                case "/api/forwards/diagnose" -> {
                    exact(fields, "port"); var result = forwards.diagnose(AppSettings.parsePort(fields.get("port"), "port"));
                    boolean failed = result.checks().stream().anyMatch(check -> check.status().equals("failed"));
                    record(failed ? "WARN" : "INFO", "映射诊断", result.port(), failed ? "诊断发现连接异常" : "映射诊断已完成",
                            String.join("\n", result.checks().stream().map(check -> check.name() + "：" + check.status() + " · " + check.detail()).toList()));
                    json(exchange, Map.of("port", result.port(), "checkedAt", result.checkedAt(), "note", result.note(),
                            "checks", result.checks().stream().map(check -> Map.of("name", check.name(), "status", check.status(),
                                    "detail", check.detail(), "durationMs", check.durationMs())).toList()));
                }
                case "/api/settings" -> {
                    exact(fields, "public"); requireConfig();
                    if (!Set.of("true", "false").contains(fields.get("public"))) throw new IllegalArgumentException("public 只能是 true 或 false");
                    boolean enabled = Boolean.parseBoolean(fields.get("public"));
                    AdminSettingsFile.setPublicAccess(configFile, enabled); publicAccess = enabled;
                    operationCompleted = true;
                    record("INFO", "访问设置", null, enabled ? "开启管理页外网访问" : "关闭管理页外网访问", "");
                    json(exchange, Map.of("message", enabled ? "外网访问已开启" : "外网访问已关闭"));
                }
                case "/api/password" -> {
                    exact(fields, "currentPassword", "newPassword"); requireConfig();
                    if (externalPassword) { reply(exchange, 409, "管理密码由环境变量提供，请修改环境变量并重启程序"); return; }
                    synchronized (this) {
                        if (!equal(token, fields.get("currentPassword"))) { reply(exchange, 401, "当前密码不正确"); return; }
                        AdminSettingsFile.changePassword(configFile, token, fields.get("newPassword")); token = fields.get("newPassword");
                        operationCompleted = true;
                        if (journal != null) journal.addSecret(token);
                        record("INFO", "密码修改", null, "管理密码已修改", "旧密码已失效，所有页面需重新登录");
                    }
                    json(exchange, Map.of("message", "密码已修改，请重新登录"));
                }
                default -> throw new IllegalArgumentException("不支持的操作");
            }
        } catch (MethodNotAllowed e) { exchange.getResponseHeaders().set("Allow", e.method); reply(exchange, 405, "请求方法不支持");
        } catch (IllegalArgumentException | ArithmeticException e) { recordFailure(exchange, operationPort, operationCompleted, e); reply(exchange, 400, e.getMessage());
        } catch (IOException | IllegalStateException e) { recordFailure(exchange, operationPort, operationCompleted, e); reply(exchange, 409, e.getMessage());
        } catch (Exception e) { recordFailure(exchange, operationPort, operationCompleted, e); reply(exchange, 500, "操作失败，请查看程序日志");
        } finally { exchange.close(); }
    }

    private void record(String level, String category, Integer selectedPort, String message, String detail) {
        if (journal != null) journal.append("event", level, category, selectedPort, List.of(), message, detail, "management");
    }
    private void recordFailure(HttpExchange exchange, Integer selectedPort, boolean completed, Exception error) {
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestMethod().equals("POST") && !path.equals("/api/events") && !path.endsWith("/preview")
                && !token.isEmpty() && equal("Bearer " + token, exchange.getRequestHeaders().getFirst("Authorization")))
            record("WARN", completed ? "响应异常" : "操作失败", selectedPort,
                    completed ? "配置已应用，管理响应未完成" : "管理操作未完成", path + " · " + error.getMessage());
    }
    private static long number(Map<String, String> fields, String key, long fallback) {
        if (!fields.containsKey(key)) return fallback;
        try { return Long.parseLong(fields.get(key)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须是有效整数"); }
    }

    private Map<String, Object> mutationState() {
        var state = state();
        long pending = forwards.list().stream().filter(item -> item.enabled() && !item.connected()).count();
        state.put("message", client ? (pending > 0 ? "配置已保存，" + pending + " 个映射等待服务端注册" : "配置已保存，已注册映射的新连接使用最新目标") : "端口配置已保存并应用");
        state.put("saved", true); state.put("pending", pending); return state;
    }
    private Map<String, Object> state() {
        synchronized (forwards) {
            var result = new LinkedHashMap<String, Object>();
            result.put("forwards", forwards.list().stream().map(ForwardPayloads::status).toList());
            if (client) {
                var connection = forwards.connectionStatus();
                if (connection != null) result.put("connection", ForwardPayloads.connection(connection));
            }
            result.put("serverTime", System.currentTimeMillis()); result.put("role", client ? "client" : "server"); return result;
        }
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
        return decodeFields(new String(bytes, StandardCharsets.UTF_8));
    }
    private static HashMap<String, String> decodeFields(String encoded) {
        var fields = new HashMap<String, String>();
        for (String part : encoded.split("&")) {
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
