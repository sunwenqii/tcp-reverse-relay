package dev.sunwenqi.tcpreverserelay;

import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.config.ConfigFiles;
import dev.sunwenqi.tcpreverserelay.config.SecurityConfig;
import dev.sunwenqi.tcpreverserelay.management.ManagementUi;
import dev.sunwenqi.tcpreverserelay.serverside.ServerRuntime;
import dev.sunwenqi.tcpreverserelay.config.AdminTokenFile;
import dev.sunwenqi.tcpreverserelay.observability.RuntimeEvents;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * <p>
 * TCP Reverse Relay 服务端启动入口
 * </p>
 *
 * @author Sunwenqi
 * @since 2026-01-08
 */
public class ServerApp {

    public static void main(String[] args) throws Exception {
        if (args.length != 0)
            throw new IllegalArgumentException("服务端不接受启动参数；请编辑当前工作目录下的 config/server.properties");
        Path file = ConfigFiles.server();
        AppSettings.Server settings = AppSettings.server(file);
        SecurityConfig.validate(settings.mode(), settings.keys());
        ServerRuntime runtime = new ServerRuntime(file, settings);
        try (var events = RuntimeEvents.open(file, "server", settings.keys().aesKey(), settings.keys().tokenKey(), AdminTokenFile.resolve(file));
             ManagementUi ui = new ManagementUi(settings.adminPort(), false, runtime, file, events.journal())) {
            try {
                runtime.start();
                ui.start();
                RuntimeEvents.event("server", "INFO", "程序启动", null, "服务端已启动", "握手端口: " + settings.handshakePort());
                System.out.println("服务端已启动，握手端口: " + settings.handshakePort());
                new CountDownLatch(1).await();
            } catch (Exception e) {
                RuntimeEvents.event("server", "ERROR", "运行异常", null, "服务端运行异常", String.valueOf(e));
                throw e;
            } finally {
                runtime.close();
                RuntimeEvents.event("server", "INFO", "程序停止", null, "服务端已停止", "");
            }
        }
    }
}
