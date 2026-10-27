package dev.sunwenqi.tcpreverserelay.integration;

import com.alibaba.fastjson.JSON;
import dev.sunwenqi.tcpreverserelay.channel.InteractiveChannel;
import dev.sunwenqi.tcpreverserelay.clientside.ClientRuntime;
import dev.sunwenqi.tcpreverserelay.config.*;
import dev.sunwenqi.tcpreverserelay.executor.*;
import dev.sunwenqi.tcpreverserelay.management.ManagementUi;
import dev.sunwenqi.tcpreverserelay.model.*;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import dev.sunwenqi.tcpreverserelay.nio.NioHallows;
import dev.sunwenqi.tcpreverserelay.serverside.ServerRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionSettingsIntegrationTest {
    @TempDir Path dir;
    @BeforeEach void startLoop() { NioHallows.INSTANCE.start(); }
    @AfterEach void stopLoop() { NioHallows.INSTANCE.cancel(); RelayExecutor.resetExecutor(new SimpleExecutor()); }
    static int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        while (System.nanoTime() < until) { if (condition.getAsBoolean()) return; Thread.sleep(30); }
        fail("未及时获得预期连接状态");
    }
    static void echo(Socket socket) throws Exception {
        socket.setSoTimeout(4000); socket.getOutputStream().write(new byte[]{1,2,3,4});
        assertArrayEquals(new byte[]{1,2,3,4}, socket.getInputStream().readNBytes(4));
    }
    static HttpResponse<String> request(int admin, String path, String fields, boolean authenticated) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + admin + path)).timeout(java.time.Duration.ofSeconds(5));
        if (authenticated) builder.header("Authorization", "Bearer fixture-pass");
        if (fields != null) builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(fields));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    static final class EchoTarget implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0);
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        EchoTarget() throws Exception {
            var accept = new Thread(() -> {
                while (!listener.isClosed()) try {
                    Socket socket = listener.accept(); sockets.add(socket);
                    var worker = new Thread(() -> { try (socket) {
                        byte[] bytes = new byte[4096]; int count;
                        while ((count = socket.getInputStream().read(bytes)) >= 0) socket.getOutputStream().write(bytes,0,count);
                    } catch (IOException ignored) {} finally { sockets.remove(socket); } }, "connection-fixture-echo");
                    worker.setDaemon(true); worker.start();
                } catch (IOException ignored) {}
            }, "connection-fixture-accept"); accept.setDaemon(true); accept.start();
        }
        public void close() throws Exception { listener.close(); for (var socket : sockets) socket.close(); }
    }
    @ParameterizedTest @ValueSource(strings = {"simple", "secretAll"}) @Timeout(35)
    void webChangePersistsReconnectsAndKeepsMappingsAndIdentity(String mode) throws Exception {
        int handshake = freePort(), serverAdmin = freePort(), admin = freePort(), remote = freePort(), paused = freePort(), unavailable = freePort();
        Path sf = dir.resolve("server.properties"), cf = dir.resolve("client.properties");
        String common = "mode=" + mode + "\nADMIN_TOKEN=fixture-pass\nAES_KEY=" + Base64.getEncoder().encodeToString(new byte[32]) + "\nTOKEN_KEY=" + "x".repeat(32) + "\n";
        try (var target = new EchoTarget()) {
            Files.writeString(sf, common + "handshake.port=" + handshake + "\nadmin.port=" + serverAdmin + "\nforwards=" + remote + "\n");
            Files.writeString(cf, common + "# preserve this comment\nserver.host=127.0.0.1\nhandshake.port=" + handshake + "\nadmin.port=" + admin
                    + "\nclient.id=web-connection\nclient.name=Connection fixture\nhealth.enabled=false\nforwards=" + remote + ":" + target.listener.getLocalPort()
                    + "," + paused + ":" + target.listener.getLocalPort() + "\ndisabled.forwards=" + paused + "\n");
            var server = new ServerRuntime(sf, AppSettings.server(sf)); var client = new ClientRuntime(cf, AppSettings.client(cf));
            ClientRuntime restarted = null;
            try (var ui = new ManagementUi(admin, true, client, cf); var sui = new ManagementUi(serverAdmin, false, server, sf)) {
                server.start(); client.start(); ui.start(); sui.start(); await(() -> client.list().get(0).connected());
                String original = Files.readString(cf);
                assertEquals(401, request(admin,"/api/connection/settings",null,false).statusCode());
                assertEquals(401, request(admin,"/api/connection/settings","host=localhost&port="+handshake,false).statusCode());
                assertEquals(404, request(serverAdmin,"/api/connection/settings",null,true).statusCode());
                var snapshot = JSON.parseObject(request(admin,"/api/connection/settings",null,true).body());
                assertEquals(handshake,snapshot.getIntValue("port")); assertEquals(1,snapshot.getIntValue("registeredMappings"));
                assertFalse(snapshot.containsKey("AES_KEY")); assertFalse(snapshot.containsKey("ADMIN_TOKEN"));
                try (Socket active = new Socket("127.0.0.1",remote)) {
                    echo(active);
                    for (String fields : List.of("host=http%3A%2F%2Fexample.com&port="+handshake, "host=localhost&port=0", "host=localhost&port="+admin,
                            "host=localhost&port="+handshake+"&mode=simple")) assertEquals(400,request(admin,"/api/connection/settings",fields,true).statusCode());
                    assertEquals(original,Files.readString(cf)); echo(active);
                    var saved = request(admin,"/api/connection/settings","host=localhost&port="+unavailable,true);
                    assertEquals(200,saved.statusCode()); assertTrue(JSON.parseObject(saved.body()).getBooleanValue("saved"));
                    try { assertEquals(-1,active.getInputStream().read()); } catch (IOException e) { assertFalse(e instanceof SocketTimeoutException); }
                }
                await(() -> !client.list().get(0).lastError().isEmpty());
                assertEquals("failed",client.diagnose(remote).checks().get(0).status(),"诊断必须检查修改后的服务器");
                assertEquals(200,request(admin,"/api/connection/settings",null,true).statusCode());
                var saved = AppSettings.client(cf); assertEquals("localhost",saved.serverHost()); assertEquals(unavailable,saved.handshakePort());
                assertEquals("web-connection",saved.clientId()); assertEquals(Set.of(paused),saved.disabledForwards()); assertEquals(2,saved.forwards().size());
                assertTrue(Files.readString(cf).contains("# preserve this comment"));
                int added = freePort(); server.upsert(null,added); client.upsert(null,added,target.listener.getLocalPort());
                await(() -> client.list().stream().filter(item -> item.port()==added).anyMatch(item -> !item.lastError().isEmpty()));
                assertFalse(server.list().stream().filter(item -> item.port()==added).findFirst().orElseThrow().connected(),"新增映射也必须使用新服务器设置");
                assertEquals(200,request(admin,"/api/connection/settings","host=127.0.0.1&port="+handshake,true).statusCode());
                await(() -> client.connectionStatus().registeredMappings()==2);
                try (Socket active = new Socket("127.0.0.1",remote)) {
                    echo(active);
                    var same = JSON.parseObject(request(admin,"/api/connection/settings","host=127.0.0.1&port="+handshake,true).body());
                    assertFalse(same.getBooleanValue("changed")); echo(active);
                    String healthy = Files.readString(cf); Files.writeString(cf,healthy+"server.host=duplicate\n");
                    assertEquals(409,request(admin,"/api/connection/settings","host=localhost&port="+unavailable,true).statusCode());
                    assertEquals(handshake,client.connectionStatus().server().port()); echo(active); Files.writeString(cf,healthy);
                }
                client.close(); restarted = new ClientRuntime(cf,AppSettings.client(cf)); restarted.start();
                ClientRuntime restored = restarted; await(() -> restored.connectionStatus().registeredMappings()==2);
                assertEquals(Set.of(paused),AppSettings.client(cf).disabledForwards());
            } finally { if (restarted!=null) restarted.close(); client.close(); server.close(); }
        }
    }
    @Test @Timeout(10) void connectionCanBeConfiguredBeforeAnyMappingExists() throws Exception {
        Path file=dir.resolve("empty.properties"); int admin=freePort(), handshake=freePort(), next=freePort();
        Files.writeString(file,"mode=simple\nserver.host=127.0.0.1\nhandshake.port="+handshake+"\nadmin.port="+admin
                +"\nADMIN_TOKEN=fixture-pass\nhealth.enabled=false\nforwards=\n");
        var client=new ClientRuntime(file,AppSettings.client(file));
        try(var ui=new ManagementUi(admin,true,client,file)) {
            client.start();ui.start();
            var response=request(admin,"/api/connection/settings","host=relay.example.invalid&port="+next,true);
            assertEquals(200,response.statusCode());var result=JSON.parseObject(response.body());
            assertTrue(result.getString("message").contains("新增或启用"));
            assertEquals(0,result.getJSONObject("connection").getIntValue("enabledMappings"));
            assertEquals("relay.example.invalid",AppSettings.client(file).serverHost());assertTrue(client.list().isEmpty());
        } finally { client.close(); }
    }
    @Test @Timeout(18) void lateHandshakeFromOldServerIsClosedAfterSwitch() throws Exception {
        var accepted = new CountDownLatch(1); var reply = new CountDownLatch(1); var peerResult = new CompletableFuture<Integer>();
        try (var slow = new ServerSocket(0)) {
            slow.setSoTimeout(6000);
            var peer = new Thread(() -> { try (Socket socket=slow.accept()) {
                socket.setSoTimeout(6000); var channel=new InteractiveChannel(); channel.setSocket(socket); channel.setCharset(StandardCharsets.UTF_8);
                var registration=channel.read(); accepted.countDown(); reply.await(4,TimeUnit.SECONDS);
                channel.writeAndFlush(InteractiveModel.of(registration.getInteractiveSeq(),InteractiveTypeEnum.COMMON_REPLY,RelayResultModel.ofSuccess(Map.of("protocolVersion",3))));
                peerResult.complete(socket.getInputStream().read());
            } catch(Exception e) { peerResult.completeExceptionally(e); } },"slow-registration-peer"); peer.setDaemon(true); peer.start();
            Path file=dir.resolve("client.properties");
            Files.writeString(file,"mode=simple\nserver.host=127.0.0.1\nhandshake.port="+slow.getLocalPort()+"\nadmin.port="+freePort()
                    +"\nclient.id=pending-switch\nclient.name=fixture\nhealth.enabled=false\nforwards="+freePort()+":"+freePort()+"\n");
            var client=new ClientRuntime(file,AppSettings.client(file));
            try {
                client.start(); assertTrue(accepted.await(4,TimeUnit.SECONDS));
                client.configureConnection(new ServerConnection("localhost",freePort())); reply.countDown();
                assertEquals(-1,peerResult.get(5,TimeUnit.SECONDS)); assertFalse(client.list().get(0).connected());
            } finally { reply.countDown(); client.close(); }
        }
    }
}
