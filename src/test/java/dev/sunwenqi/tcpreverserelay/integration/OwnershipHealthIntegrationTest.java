package dev.sunwenqi.tcpreverserelay.integration;

import com.alibaba.fastjson.JSON;
import dev.sunwenqi.tcpreverserelay.clientside.ClientRuntime;
import dev.sunwenqi.tcpreverserelay.config.*;
import dev.sunwenqi.tcpreverserelay.executor.*;
import dev.sunwenqi.tcpreverserelay.health.HealthSettings;
import dev.sunwenqi.tcpreverserelay.management.ManagementUi;
import dev.sunwenqi.tcpreverserelay.nio.NioHallows;
import dev.sunwenqi.tcpreverserelay.serverside.ServerRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipHealthIntegrationTest {
    @TempDir Path dir;
    static int port() throws Exception { try(var s=new ServerSocket(0)){return s.getLocalPort();} }
    static void await(BooleanSupplier test, int millis) throws Exception {
        long until=System.nanoTime()+millis*1_000_000L;
        while(System.nanoTime()<until) { if(test.getAsBoolean()) return; Thread.sleep(40); } fail("状态等待超时");
    }
    Path clientFile(String id,int handshake,int admin,int remote,int target) throws Exception {
        Path file=dir.resolve(id+".properties"); Files.writeString(file,"mode=simple\nserver.host=127.0.0.1\nhandshake.port="+handshake+"\nadmin.port="+admin
                +"\nADMIN_TOKEN=fixture-pass\nclient.id="+id+"\nclient.name="+id+"\nhealth.interval.seconds=3\nhealth.timeout.millis=200\nforwards="+remote+":"+target+"\n"); return file;
    }
    @Test @Timeout(55) void conflictingClientCannotReplaceOwnerAndHealthSurvivesReconnect() throws Exception {
        NioHallows.INSTANCE.start(); int handshake=port(), admin=port(), clientAdmin=port(), remote=port();
        Path sf=dir.resolve("server.properties"); Files.writeString(sf,"mode=simple\nhandshake.port="+handshake+"\nadmin.port="+admin+"\nADMIN_TOKEN=fixture-pass\nforwards="+remote+"\n");
        var target=new ServerSocket(0); int targetPort=target.getLocalPort();
        Thread accept=new Thread(()->{while(!target.isClosed())try(var socket=target.accept()){}catch(Exception ignored){}},"health-target");accept.setDaemon(true);accept.start();
        Path af=clientFile("device-A",handshake,clientAdmin,remote,targetPort),bf=clientFile("device-B",handshake,port(),remote,targetPort);
        var server=new ServerRuntime(sf,AppSettings.server(sf)); var a=new ClientRuntime(af,AppSettings.client(af)); var b=new ClientRuntime(bf,AppSettings.client(bf));
        try(var ui=new ManagementUi(admin,false,server,sf);var cui=new ManagementUi(clientAdmin,true,a,af)) {
            server.start(); ui.start(); cui.start(); a.start();
            await(()->server.list().get(0).connected(),5000); assertEquals("device-A",server.list().get(0).ownerId());
            await(()->"up".equals(server.list().get(0).health().state()),13000);
            b.start(); await(()->b.list().get(0).lastError().contains("device-A"),5000);
            assertTrue(a.list().get(0).connected()); assertEquals("device-A",server.list().get(0).clientId());
            target.close(); await(()->"down".equals(a.list().get(0).health().state()),10000);
            await(()->"down".equals(server.list().get(0).health().state()),12000); assertTrue(server.list().get(0).connected());
            var http=HttpClient.newHttpClient(); URI home=URI.create("http://127.0.0.1:"+admin);
            var noAuth=http.send(HttpRequest.newBuilder(home.resolve("/api/forwards/owner")).POST(HttpRequest.BodyPublishers.ofString("port="+remote+"&clientId=device-B")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,noAuth.statusCode());
            var transfer=http.send(HttpRequest.newBuilder(home.resolve("/api/forwards/owner")).header("Authorization","Bearer fixture-pass")
                    .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("port="+remote+"&clientId=device-B")).build(),HttpResponse.BodyHandlers.ofString()); assertEquals(200,transfer.statusCode());
            await(()->"device-B".equals(server.list().get(0).clientId())&&server.list().get(0).connected(),15000);
            assertFalse(a.list().get(0).connected()); assertEquals("device-B",AppSettings.server(sf).portOwners().get(remote));
            a.configureHealth(new HealthSettings(false,3,200,2));assertFalse(AppSettings.client(af).health().enabled());
            var get=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+clientAdmin+"/api/health/settings")).header("Authorization","Bearer fixture-pass").build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,get.statusCode());assertFalse(JSON.parseObject(get.body()).getBooleanValue("enabled"));
            server.batch("disable",String.valueOf(remote),false);assertEquals("device-B",AppSettings.server(sf).portOwners().get(remote));
            int renamed=port(); server.upsert(remote,renamed); assertEquals("device-B",AppSettings.server(sf).portOwners().get(renamed));
            server.delete(renamed); assertTrue(AppSettings.server(sf).portOwners().isEmpty());
        } finally { target.close();a.close();b.close();server.close();NioHallows.INSTANCE.cancel();RelayExecutor.resetExecutor(new SimpleExecutor()); }
    }
}
