package dev.sunwenqi.tcpreverserelay.serverside.listen;

import dev.sunwenqi.tcpreverserelay.api.IBelongControl;
import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.common.CommonFormat;
import dev.sunwenqi.tcpreverserelay.config.ResourceLimits;
import dev.sunwenqi.tcpreverserelay.executor.RelayExecutor;
import dev.sunwenqi.tcpreverserelay.nio.INioProcessor;
import dev.sunwenqi.tcpreverserelay.nio.NioHallows;
import dev.sunwenqi.tcpreverserelay.serverside.listen.clear.IClearInvalidSocketPartThread;
import dev.sunwenqi.tcpreverserelay.serverside.listen.config.IListenServerConfig;
import dev.sunwenqi.tcpreverserelay.serverside.listen.control.IControlSocket;
import dev.sunwenqi.tcpreverserelay.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import dev.sunwenqi.tcpreverserelay.model.interactive.ClientControlModel;
import dev.sunwenqi.tcpreverserelay.model.interactive.TargetProbeResult;
import dev.sunwenqi.tcpreverserelay.observability.RuntimeEvents;

/**
 * <p>
 * 监听服务进程
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-07-05 10:53:33
 */
@Slf4j
public final class ServerListenThread implements Runnable, INioProcessor, IBelongControl, IServerListen {

    private static final int MAX_CONNECTIONS_PER_PORT =
            ResourceLimits.fromEnv("TCP_REVERSE_RELAY_MAX_CONNECTIONS_PER_PORT", 128, 512);

    /**
     * -- GETTER --
     * 获取配置
     *
     * @return
     */
    @Getter
    private final IListenServerConfig config;
    private final ServerSocket listenServerSocket;
    private final ConcurrentHashMap<String, AbsSocketPart> socketPartMap = new ConcurrentHashMap<>();
    private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS_PER_PORT);
    private volatile Thread myThread = null;
    private volatile boolean isAlive = false;
    /**
     * -- GETTER --
     * 是否已退出
     *
     * @return
     */
    @Getter
    private volatile boolean canceled = false;
    private volatile IControlSocket controlSocket;
    private volatile ClientRegistration registration;
    private volatile IClearInvalidSocketPartThread clearInvalidSocketPartThread;

    public ServerListenThread(IListenServerConfig config) throws Exception {
        this.config = config;

        // 此处就开始占用端口，防止重复占用端口，和启动时已被占用
        this.listenServerSocket = this.config.createServerSocket();

        log.info("server listen port[{}] is created!", this.getListenPort());
    }

    @Override
    public void run() {
        while (this.isAlive) {
            try {
                Socket listenSocket = this.listenServerSocket.accept();
                this.procMethod(listenSocket);
            } catch (Exception e) {
                log.warn("监听服务[" + this.getListenPort() + "]服务异常", e);
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
            log.warn("监听服务[" + this.getListenPort() + "]服务异常", e);
            this.cancel();
        }
    }

    /**
     * 任务执行方法
     *
     * @param listenSocket
     */
    private void procMethod(Socket listenSocket) {
        if (!this.connectionSlots.tryAcquire()) {
            try {
                listenSocket.close();
            } catch (IOException ignored) {
                // 已达到该映射端口的连接上限。
            }
            return;
        }
        try {
            RelayExecutor.executeServerListenAccept(() -> {
                String socketPartKey = null;
                boolean inMap = false;
                try {
                    if (Objects.isNull(this.controlSocket)) {
                        return;
                    }
                    socketPartKey = CommonFormat.generateSocketPartKey(this.getListenPort());
                    AbsSocketPart socketPart = this.config.newSocketPart(this);
                    socketPart.setSocketPartKey(socketPartKey);
                    socketPart.setRecvSocket(listenSocket);
                    if (this.socketPartMap.putIfAbsent(socketPartKey, socketPart) != null) {
                        return;
                    }
                    inMap = true;
                    if (!this.sendClientWait(socketPartKey)) {
                        this.stopSocketPart(socketPartKey);
                    }
                } catch (Exception e) {
                    log.warn("处理映射连接失败", e);
                    if (inMap) {
                        this.stopSocketPart(socketPartKey);
                    }
                } finally {
                    if (!inMap) {
                        try {
                            listenSocket.close();
                        } catch (IOException ignored) {
                            // 连接已退出。
                        }
                        this.connectionSlots.release();
                    }
                }
            });
        } catch (RuntimeException e) {
            this.connectionSlots.release();
            try {
                listenSocket.close();
            } catch (IOException ignored) {
                // 任务未被接收。
            }
            log.warn("映射连接任务被拒绝", e);
        }
    }

    /**
     * * 告知客户端，有新连接
     *
     * @param socketPartKey
     * @author Sunwenqi
     * @since 2024-07-11 15:45:14
     */
    private boolean sendClientWait(String socketPartKey) {
        log.debug("告知新连接 sendClientWait[{}]", socketPartKey);
        boolean sendClientWait;

        IControlSocket controlSocket = this.controlSocket;
        if (controlSocket == null) {
            return false;
        }

        try {
            sendClientWait = controlSocket.sendClientWait(socketPartKey);
        } catch (Throwable e) {
            log.error("告知新连接 sendClientWait[" + socketPartKey + "] 发生未知异常", e);
            sendClientWait = false;
        }

        if (!sendClientWait) {
            log.warn("告知新连接 sendClientWait[" + socketPartKey + "] 失败");
            if (!controlSocket.isValid()) {
                // 保证control为置空状态
                this.stopListen();
            }
            return false;
        }
        return true;
    }

    /**
     * * 启动
     *
     * @throws Exception
     * @author Sunwenqi
     * @since 2025-01-07 09:36:30
     */
    private void start() {
        Assert.state(!this.canceled, "已退出，不得重新启动");
        if (this.isAlive) {
            return;
        }
        this.isAlive = true;

        log.info("server listen port[{}] starting ...", this.getListenPort());

        if (this.clearInvalidSocketPartThread == null) {
            this.clearInvalidSocketPartThread = this.config.newClearInvalidSocketPartThread(this);
            if (this.clearInvalidSocketPartThread != null) {
                this.clearInvalidSocketPartThread.start();
            }
        }

        ServerSocketChannel channel = this.listenServerSocket.getChannel();
        if (Objects.nonNull(channel)) {
            try {
                NioHallows.register(channel, SelectionKey.OP_ACCEPT, this);
            } catch (IOException e) {
                log.error("register serverListen channel[{}] failed!", config.getListenPort());
                this.cancel();
                throw new RuntimeException("nio注册时异常", e);
            }
        } else {
            Thread myThread = this.myThread;
            if (myThread == null || !myThread.isAlive()) {
                myThread = this.myThread = new Thread(this);
                myThread.setName("server-listen-" + this.formatInfo());
                myThread.start();
            }
        }

        log.info("server listen port[{}] start success!", this.getListenPort());
    }

    /**
     * * 关停监听服务，不注销已经建立的，并置空controlSocket
     *
     * @author Sunwenqi
     * @since 2024-07-18 18:43:43
     */
    private synchronized void stopListen() {
        log.info("stopListen[{}]", this.getListenPort());
        boolean disconnected = this.isAlive && !canceled;
        this.isAlive = false;
        if (disconnected) RuntimeEvents.event("server", "WARN", "客户端离线", getListenPort(), "客户端控制连接已断开",
                registration == null ? "" : registration.name() + " · " + registration.address());

        NioHallows.release(this.listenServerSocket.getChannel());

        Thread myThread;
        if ((myThread = this.myThread) != null) {
            this.myThread = null;
            myThread.interrupt();
        }

        IControlSocket controlSocket = this.controlSocket;
        if (Objects.nonNull(controlSocket)) {
            if (registration != null) registration = registration.seen(controlSocket.getLastActivityAt());
            this.controlSocket = null;
            try {
                controlSocket.close();
            } catch (Exception e) {
                log.debug("监听服务控制端口关闭异常", e);
            }
        }
    }

    /**
     * * 退出
     *
     * @author Sunwenqi
     * @since 2025-01-07 09:37:00
     */
    public synchronized void cancel() {
        if (this.canceled) {
            return;
        }
        this.canceled = true;

        log.info("serverListen cancelling[{}]", this.getListenPort());

        ListenServerControl.remove(this.getListenPort());

        this.stopListen();

        try {
            this.listenServerSocket.close();
            // A closed channel remains registered until the selector processes cancellation.
            // Wait briefly for deregistration so an immediate resume can bind the port again.
            var channel = this.listenServerSocket.getChannel();
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500);
            while (channel != null && channel.isRegistered() && System.nanoTime() < until)
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        } catch (Exception e) {
            // do nothing
        }

        IClearInvalidSocketPartThread clearInvalidSocketPartThread;
        if ((clearInvalidSocketPartThread = this.clearInvalidSocketPartThread) != null) {
            this.clearInvalidSocketPartThread = null;
            try {
                clearInvalidSocketPartThread.cancel();
            } catch (Exception e) {
                // do nothing
            }
        }

        String[] socketPartKeyArray = this.socketPartMap.keySet().toArray(new String[0]);
        for (String key : socketPartKeyArray) {
            this.stopSocketPart(key);
        }

        log.debug("serverListen cancel[{}] is success", this.getListenPort());
    }

    /**
     * * 停止指定的端口
     *
     * @param socketPartKey
     * @return
     * @author Sunwenqi
     * @since 2025-07-11 16:33:10
     */
    @Override
    public boolean stopSocketPart(String socketPartKey) {
        log.debug("停止接口 stopSocketPart[{}]", socketPartKey);
        AbsSocketPart socketPart = this.socketPartMap.remove(socketPartKey);
        if (socketPart == null) {
            return false;
        }
        try {
            socketPart.cancel();
        } finally {
            this.connectionSlots.release();
        }
        return true;
    }

    /**
     * * 清理无效socketPart
     *
     * @author Sunwenqi
     * @since 2025-08-21 12:50:57
     */
    public void clearInvalidSocketPart() {
        log.debug("clearInvalidSocketPart[{}]", this.getListenPort());

        ConcurrentHashMap<String, AbsSocketPart> socketPartMap = this.socketPartMap;

        Set<String> keySet = socketPartMap.keySet();
        // 被去除的时候 set会变化而导致空值问题
        String[] array = keySet.toArray(new String[0]);

        for (String key : array) {
            AbsSocketPart socketPart = socketPartMap.get(key);
            if (socketPart != null && !socketPart.isValid()) {
                this.stopSocketPart(key);
            }
        }

    }

    /**
     * * 将接受到的连接进行设置组合
     *
     * @param socketPartKey
     * @param sendSocket
     * @return
     */
    public boolean doSetPartClient(String socketPartKey, Socket sendSocket) {
        log.debug("接入接口 doSetPartClient[{}]", socketPartKey);
        AbsSocketPart socketPart = this.socketPartMap.get(socketPartKey);
        if (socketPart == null) {
            return false;
        }
        socketPart.setSendSocket(sendSocket);

        boolean createPassWay = socketPart.createPassWay();
        if (!createPassWay) {
            socketPart.cancel();
            this.stopSocketPart(socketPartKey);
            return false;
        }

        return true;
    }

    /**
     * * 设置控制端口
     *
     * @param socket
     */
    public synchronized void setControlSocket(Socket socket) {
        setControlSocket(socket, new ClientControlModel(this.getListenPort()));
    }

    public synchronized void setControlSocket(Socket socket, ClientControlModel info) {
        ClientRegistration nextRegistration = ClientRegistration.from(socket, info);
        log.info("setControlSocket[{}]", this.getListenPort());

        IControlSocket controlSocketNew = this.config.newControlSocket(socket, null);

        IControlSocket controlSocket;
        if ((controlSocket = this.controlSocket) != null) {
            this.controlSocket = null;
            try {
                controlSocket.replaceClose();
            } catch (Exception e) {
                log.debug("监听服务控制端口关闭异常", e);
            }
        }

        controlSocketNew.setServerListen(this);
        this.registration = nextRegistration;
        this.controlSocket = controlSocketNew;
        controlSocketNew.startRecv();
        this.start();
        RuntimeEvents.event("server", "INFO", "客户端接入", getListenPort(), "客户端已注册此端口",
                nextRegistration.name() + " · " + nextRegistration.address());
    }

    @Override
    public synchronized void updateClientInfo(IControlSocket control, ClientControlModel info) {
        if (control != controlSocket || registration == null) return;
        // Source address and connection timestamps remain owned by the server.
        ClientRegistration update = new ClientRegistration(info.getClientId(), info.getClientName(), registration.address(),
                info.getClientVersion(), info.getProtocolVersion(),
                dev.sunwenqi.tcpreverserelay.config.TargetAddress.validate(info.getTargetHost()),
                dev.sunwenqi.tcpreverserelay.config.AppSettings.parsePort(String.valueOf(info.getTargetPort()), "目标端口"),
                registration.connectedAt(), System.currentTimeMillis());
        if (update.name() == null || update.name().isBlank() || update.name().length() > 64
                || update.name().chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("无效的客户端名称");
        registration = registration.updated(update);
    }

    public ClientRegistration registrationSnapshot() {
        ClientRegistration current = registration;
        IControlSocket control = controlSocket;
        return current == null ? null : current.seen(control == null ? 0 : control.getLastActivityAt());
    }

    public TargetProbeResult probeTarget() throws Exception {
        IControlSocket control = controlSocket;
        ClientRegistration current = registration;
        if (control == null || !isAlive) throw new IOException("客户端未连接");
        if (current == null || current.protocol() < 2) throw new IOException("客户端版本不支持远程诊断，请升级客户端");
        return control.probeTarget();
    }

    @Override
    public synchronized void controlCloseNotice(IControlSocket controlSocket) {
        if (Objects.equals(controlSocket, this.controlSocket)) {
            this.stopListen();
        }
    }

    /**
     * * 获取监听端口
     *
     * @return
     * @author Sunwenqi
     * @since 2025-07-18 18:45:57
     */
    public Integer getListenPort() {
        return this.config.getListenPort();
    }

    /**
     * * 获取已建立的连接
     *
     * @return
     * @author Sunwenqi
     * @since 2024-07-19 16:32:09
     */
    public List<String> getSocketPartList() {
        return new LinkedList<>(this.socketPartMap.keySet());
    }

    /**
     * * 获取socket对
     *
     * @return
     * @author Sunwenqi
     * @since 2025-01-07 09:43:08
     */
    public Map<String, AbsSocketPart> getSocketPartMap() {
        return this.socketPartMap;
    }

    /**
     * * 是否激活状态
     *
     * @return
     * @author Sunwenqi
     * @since 2025-01-07 09:43:28
     */
    public boolean isAlive() {
        return this.isAlive;
    }

    public String formatInfo() {
        return String.valueOf(this.getListenPort());
    }

}
