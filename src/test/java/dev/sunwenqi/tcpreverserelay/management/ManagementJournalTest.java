package dev.sunwenqi.tcpreverserelay.management;

import com.alibaba.fastjson.JSON;
import dev.sunwenqi.tcpreverserelay.forward.*;
import dev.sunwenqi.tcpreverserelay.observability.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManagementJournalTest {
    @TempDir Path root;
    @Test void authenticatedFiltersAndManagementEventsPersistWithoutPasswords() throws Exception {
        Path file=root.resolve("server.properties");Files.writeString(file,"ADMIN_TOKEN=fixture-admin-pass\nforwards=\nadmin.public=false\n");
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var manager=new ForwardManager(){
            public List<ForwardStatus> list(){return List.of();}
            public void upsert(Integer old,int selected,Integer target){}
            public void delete(int selected){}
        };
        var http=HttpClient.newHttpClient();URI home=URI.create("http://127.0.0.1:"+port);
        try(var events=RuntimeEvents.open(file,"server","fixture-admin-pass");var ui=new ManagementUi(port,false,manager,file,events.journal())) {
            ui.start();
            assertEquals(401,http.send(HttpRequest.newBuilder(home.resolve("/api/events")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            events.journal().append("event","WARN","配置",18080,List.of(),"端口异常","fixture-admin-pass","test");
            var response=http.send(get(home,"/api/events?kind=event&port=18080&q=%E7%AB%AF%E5%8F%A3&level=WARN","fixture-admin-pass"),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertEquals(1,JSON.parseObject(response.body()).getIntValue("total"));
            assertFalse(response.body().contains("fixture-admin-pass"));
            for(String query:List.of("level=DEBUG","limit=101","limit=10000000000","port=65536","before=-1","q=a&q=b","from=20&to=10","other=1"))
                assertEquals(400,http.send(get(home,"/api/events?"+query,"fixture-admin-pass"),HttpResponse.BodyHandlers.ofString()).statusCode(),query);
            assertEquals(200,http.send(get(home,"/management/journal.js","fixture-admin-pass"),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200,http.send(post(home,"/api/forwards","port=18080","fixture-admin-pass"),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200,http.send(post(home,"/api/password","currentPassword=fixture-admin-pass&newPassword=new-admin-pass","fixture-admin-pass"),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401,http.send(get(home,"/api/events","fixture-admin-pass"),HttpResponse.BodyHandlers.ofString()).statusCode());
            String body=http.send(get(home,"/api/events?kind=event","new-admin-pass"),HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("新增端口映射"));assertTrue(body.contains("管理密码已修改"));
            assertFalse(body.contains("fixture-admin-pass"));assertFalse(body.contains("new-admin-pass"));
        }
        String disk=Files.readString(root.resolve("logs/server/journal.jsonl"));
        assertTrue(disk.contains("新增端口映射"));assertFalse(disk.contains("fixture-admin-pass"));assertFalse(disk.contains("new-admin-pass"));
    }
    private static HttpRequest get(URI home,String path,String password){return HttpRequest.newBuilder(home.resolve(path)).header("Authorization","Bearer "+password).GET().build();}
    @Test void responseFailureDoesNotReportAnAppliedChangeAsRejected() throws Exception {
        Path file=root.resolve("server.properties");Files.writeString(file,"ADMIN_TOKEN=fixture-admin-pass\nforwards=\n");
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var manager=new ForwardManager(){
            boolean changed;
            public List<ForwardStatus> list(){if(changed)throw new IllegalStateException("无法读取返回状态");return List.of();}
            public void upsert(Integer old,int selected,Integer target){changed=true;}
            public void delete(int selected){}
        };
        try(var events=RuntimeEvents.open(file,"server","fixture-admin-pass");var ui=new ManagementUi(port,false,manager,file,events.journal())) {
            ui.start();var http=HttpClient.newHttpClient();URI home=URI.create("http://127.0.0.1:"+port);
            assertEquals(409,http.send(post(home,"/api/forwards","port=18080","fixture-admin-pass"),HttpResponse.BodyHandlers.ofString()).statusCode());
            String data=http.send(get(home,"/api/events?kind=event","fixture-admin-pass"),HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(data.contains("新增端口映射"));assertTrue(data.contains("配置已应用，管理响应未完成"));assertFalse(data.contains("管理操作未完成"));
        }
    }
    private static HttpRequest post(URI home,String path,String body,String password){return HttpRequest.newBuilder(home.resolve(path)).header("Authorization","Bearer "+password).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();}
}
