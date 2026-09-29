package dev.sunwenqi.tcpreverserelay.serverside.client;

import dev.sunwenqi.tcpreverserelay.config.ResourceLimits;
import dev.sunwenqi.tcpreverserelay.nio.INioProcessor;
import dev.sunwenqi.tcpreverserelay.nio.NioHallows;
import dev.sunwenqi.tcpreverserelay.serverside.client.config.IClientServiceConfig;
import dev.sunwenqi.tcpreverserelay.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 连接客户端进程
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-07-05 10:53:33
 */
@Slf4j
public final class ClientServiceThread implements Runnable, INioProcessor {

    private static final int HANDSHAKE_TIMEOUT_MILLIS = 5000;
    private static final int MAX_PENDING_HANDSHAKES =
            ResourceLimits.fromEnv("TCP_REVERSE_RELAY_HANDSHAKE_WORKERS", 32, 256);

    private final ServerSocket listenServerSocket;
    private final IClientServiceConfig<?, ?> config;
    private final ThreadPoolExecutor handshakeExecutor = new ThreadPoolExecutor(
            0, MAX_PENDING_HANDSHAKES, 60, TimeUnit.SECONDS, new SynchronousQueue<>());
    private volatile Thread myThread = null;
    private volatile boolean isAlive = false;
    @Getter
    private volatile boolean canceled = false;

    public ClientServiceThread(IClientServiceConfig<?, ?> config) throws Exception {
        this.config = config;

        // 启动时配置，若启动失败则执行cancell并再次抛出异常让上级处理
        this.listenServerSocket = config.createServerSocket();

        log.info("client service [{}] is created!", this.config.getListenPort());
    }

    @Override
    public void run() {
        while (this.isAlive) {
            try {
                Socket listenSocket = this.listenServerSocket.accept();
                this.procMethod(listenSocket);
            } catch (Exception e) {
                log.warn("客户端服务进程 轮询等待出现异常", e);
                this.cancel();
            }
        }
    }

    @Override
    public void process(SelectionKey key) {
        if (!key.isValid()) {
            this.cancel();
            return;
        }

        try {
            ServerSocketChannel channel = (ServerSocketChannel) key.channel();
            SocketChannel accept = channel.accept();
            for (; Objects.nonNull(accept); accept = channel.accept()) {
                this.procMethod(accept.socket());
            }
        } catch (IOException e) {
            log.warn("客户端服务进程 轮询等待出现异常", e);
            this.cancel();
        }
    }

    /**
     * 处理客户端发来的消息
     *
     * @author Sunwenqi
     * @since 2025-01-03 14:46:36
     */
    public void procMethod(Socket listenSocket) {
        try {
            this.handshakeExecutor.execute(() -> {
                try {
                    listenSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
                    this.config.getClientServiceAdapter().procMethod(listenSocket);
                } catch (Exception e) {
                    if (e instanceof IOException || e instanceof IllegalStateException) {
                        log.debug("握手连接已关闭: {}", e.toString());
                    } else {
                        log.warn("处理socket异常", e);
                    }
                    try {
                        listenSocket.close();
                    } catch (IOException sce) {
                        log.warn("处理新socket时异常，并在关闭socket时异常", e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // 慢连接达到上限时立即拒绝，避免无限创建线程。
            try {
                listenSocket.close();
            } catch (IOException ignored) {
                // 已经拒绝该连接。
            }
        }
    }

    /**
     * 启动
     *
     * @author Sunwenqi
     * @since 2025-01-03 14:05:59
     */
    public synchronized void start() {
        Assert.state(!this.canceled, "已退出，不得重新启动");

        log.info("client service [{}] starting ...", this.config.getListenPort());
        this.isAlive = true;

        ServerSocketChannel channel = this.listenServerSocket.getChannel();
        if (Objects.nonNull(channel)) {
            try {
                NioHallows.register(channel, SelectionKey.OP_ACCEPT, this);
            } catch (IOException e) {
                log.error("register clientService channel[{}] failed!", config.getListenPort());
                this.cancel();
                throw new RuntimeException("nio注册时异常", e);
            }
        } else {
            if (this.myThread == null || !this.myThread.isAlive()) {
                this.myThread = new Thread(this);
                this.myThread.setName("client-server-" + this.formatInfo());
                this.myThread.start();
            }
        }

        log.info("client service [{}] start success", this.config.getListenPort());
    }

    /**
     * 退出
     *
     * @author Sunwenqi
     * @since 2024-07-18 18:32:03
     */
    public synchronized void cancel() {
        if (this.canceled) {
            return;
        }
        this.canceled = true;

        log.info("client service [{}] will cancell", this.config.getListenPort());

        this.isAlive = false;
        this.handshakeExecutor.shutdownNow();

        ServerSocket listenServerSocket;
        if ((listenServerSocket = this.listenServerSocket) != null) {
            NioHallows.release(listenServerSocket.getChannel());
            try {
                listenServerSocket.close();
            } catch (IOException e) {
                log.warn("监听端口关闭异常", e);
            }
        }

        Thread myThread;
        if ((myThread = this.myThread) != null) {
            myThread.interrupt();
        }

        log.info("client service [{}] cancell success", this.config.getListenPort());
    }

    /**
     * * 是否激活状态
     *
     * @author Sunwenqi
     * @since 2025-01-07 09:43:28
     */
    public boolean isAlive() {
        return this.isAlive;
    }

    /**
     * 获取监听端口
     *
     * @author Sunwenqi
     * @since 2024-07-18 18:32:40
     */
    public Integer getListenPort() {
        return this.config.getListenPort();
    }

    /**
     * 格式化为短小的可识别信息
     *
     * @author Sunwenqi
     * @since 2025-04-15 14:17:41
     */
    public String formatInfo() {
        return String.valueOf(this.getListenPort());
    }

}
