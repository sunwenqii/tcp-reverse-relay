package dev.sunwenqi.tcpreverserelay;

import dev.sunwenqi.tcpreverserelay.clientside.ClientRuntime;
import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.config.ConfigFiles;
import dev.sunwenqi.tcpreverserelay.config.SecurityConfig;
import dev.sunwenqi.tcpreverserelay.management.ManagementUi;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * <p>
 * TCP Reverse Relay 客户端启动入口
 * </p>
 *
 * @author Sunwenqi
 * @since 2026-01-08
 */
public class ClientApp {

    /**
     * 客户端启动主方法
     *
     * @param args Java 入口参数；此程序不接受参数
     * @throws Exception 启动异常
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 0)
            throw new IllegalArgumentException("客户端不接受启动参数；请编辑当前工作目录下的 config/client.properties");
        Path file = ConfigFiles.client();
        AppSettings.Client settings = AppSettings.client(file);
        SecurityConfig.validate(settings.mode(), settings.keys());
        ClientRuntime runtime = new ClientRuntime(file, settings);
        try (ManagementUi ui = new ManagementUi(settings.adminPort(), true, runtime, file)) {
            try {
                runtime.start();
                ui.start();
                System.out.println("客户端已启动，服务端: " + settings.serverHost() + ":" + settings.handshakePort());
                new CountDownLatch(1).await();
            } finally {
                runtime.close();
            }
        }
    }
}
