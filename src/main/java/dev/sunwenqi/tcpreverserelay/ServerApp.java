package dev.sunwenqi.tcpreverserelay;

import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.config.ConfigFiles;
import dev.sunwenqi.tcpreverserelay.config.SecurityConfig;
import dev.sunwenqi.tcpreverserelay.management.ManagementUi;
import dev.sunwenqi.tcpreverserelay.serverside.ServerRuntime;

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
        try (ManagementUi ui = new ManagementUi(settings.adminPort(), false, runtime, file)) {
            try {
                runtime.start();
                ui.start();
                System.out.println("服务端已启动，握手端口: " + settings.handshakePort());
                new CountDownLatch(1).await();
            } finally {
                runtime.close();
            }
        }
    }
}
