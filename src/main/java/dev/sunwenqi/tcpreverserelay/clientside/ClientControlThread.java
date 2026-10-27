package dev.sunwenqi.tcpreverserelay.clientside;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.sunwenqi.tcpreverserelay.api.IBelongControl;
import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.clientside.adapter.IClientAdapter;
import dev.sunwenqi.tcpreverserelay.clientside.config.IClientConfig;
import dev.sunwenqi.tcpreverserelay.clientside.heart.IClientHeartThread;
import dev.sunwenqi.tcpreverserelay.forward.TcpDiagnostics;
import dev.sunwenqi.tcpreverserelay.health.HealthSnapshot;
import dev.sunwenqi.tcpreverserelay.observability.RuntimeEvents;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * 客户端控制服务
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-07-05 10:53:33
 */
@Slf4j
public final class ClientControlThread implements Runnable, IBelongControl {
    private static final ExecutorService REGISTRATIONS = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4096), task -> { Thread t = new Thread(task, "relay-registration"); t.setDaemon(true); return t; });
    private final AtomicBoolean attempting = new AtomicBoolean();
    private volatile boolean queued;
    private volatile boolean metadataDirty;
    private java.util.function.Supplier<HealthSnapshot> healthSupplier;
    public void setHealthSupplier(java.util.function.Supplier<HealthSnapshot> supplier) { healthSupplier = supplier; }
    private volatile HealthSnapshot sentHealth;
    private volatile String lastError = "";
    private volatile String targetError = "";
    private volatile long lastAttemptAt;
    private volatile long nextRetryAt;
    private volatile long connectedAt;
    private volatile String reportedRegistrationError = "";
    private volatile long reportedRegistrationAt;

    // 存储Socket部件的映射表，键为字符串类型，值为AbsSocketPart类型的对象
    private final Map<String, AbsSocketPart> socketPartMap = new ConcurrentHashMap<>();

    // 客户端配置接口的引用，泛型参数表示配置的具体类型
    private final IClientConfig<?, ?> config;

    // 记录当前实例关联的线程，初始为null
    private volatile Thread myThread = null;

    // 标记当前实例是否处于活动状态
    private volatile boolean isAlive = false;

    // 标记是否已取消操作
    @Getter
    private volatile boolean cancelled = false;

    // 客户端心跳线程接口的实现，泛型参数表示具体类型
    private volatile IClientHeartThread clientHeartThread;

    // 客户端适配器接口的实现，泛型参数表示具体类型
    private volatile IClientAdapter<?, ?> clientAdapter;

    public ClientControlThread(IClientConfig<?, ?> config) {
        this.config = config;
    }

    /**
     * 触发控制服务
     *
     * @return
     * @throws Exception
     */
    public boolean createControl() throws Exception {
        if (cancelled || !attempting.compareAndSet(false, true)) return false;
        lastAttemptAt = System.currentTimeMillis();
        try {
            IClientAdapter<?, ?> adapter;
            synchronized (this) {
                if (cancelled) return false;
                this.stopClient();
                if (this.clientAdapter == null) this.clientAdapter = this.config.newCreateControlAdapter(this);
                adapter = this.clientAdapter;
            }
            boolean flag = adapter.createControlChannel();
            synchronized (this) {
                if (cancelled) { adapter.close(); return false; }
                if (!flag) { registrationFailed("服务端未接受该端口的注册"); return false; }
                boolean reconnected = connectedAt > 0;
                connectedAt = System.currentTimeMillis();
                lastError = "";
                targetError = "";
                metadataDirty = false;
                sentHealth = null;
                this.start();
                reportedRegistrationError = "";
                RuntimeEvents.event("client", "INFO", reconnected ? "连接恢复" : "端口注册", getListenServerPort(),
                        reconnected ? "客户端控制连接已恢复" : "端口注册成功", "服务端: " + config.getClientServiceIp() + ":" + config.getClientServicePort());
                return true;
            }
        } catch (Exception e) {
            if (!cancelled) registrationFailed(TcpDiagnostics.describe(e));
            throw e;
        } finally {
            nextRetryAt = isAlive || cancelled ? 0 : System.currentTimeMillis() + 10_000;
            attempting.set(false);
        }
    }

    /**
     * 首次连接失败时也启动现有的心跳重连任务。
     */
    public void startWithReconnect() {
        if (this.clientHeartThread == null) {
            this.clientHeartThread = this.config.newClientHeartThread(this);
        }
        this.clientHeartThread.start();
        queued = true;
        try {
            REGISTRATIONS.execute(() -> {
                try { createControl(); }
                catch (Exception e) { if (!cancelled) log.warn("端口 {} 注册失败，将自动重试: {}", getListenServerPort(), lastError); }
                finally { queued = false; }
            });
        } catch (RejectedExecutionException e) {
            queued = false;
            lastError = "注册队列繁忙，稍后自动重试";
            nextRetryAt = System.currentTimeMillis() + 10_000;
        }
    }

    @Override
    public void run() {
        Thread current = Thread.currentThread();
        while (this.isAlive && this.myThread == current) {
            try {
                // 使用适配器代理执行
                this.clientAdapter.waitMessage();
            } catch (Exception e) {
                log.warn("client control [{}] to server is exception,will stopClient",
                        this.config.getListenServerPort());
                if (this.myThread == current) this.stopClient();
            }
        }
    }

    @Override
    public boolean stopSocketPart(String socketPartKey) {
        log.debug("stopSocketPart[{}]", socketPartKey);

        AbsSocketPart socketPart = this.socketPartMap.remove(socketPartKey);
        if (socketPart == null) {
            return false;
        }
        socketPart.cancel();
        return true;
    }

    /**
     * * 启动
     */
    private void start() {
        this.isAlive = true;

        // 检查并更新心跳线程
        Thread myThread = this.myThread;
        if (Objects.isNull(myThread) || !myThread.isAlive()) {
            // 心跳线程不存在或已死亡，需要重新初始化

            IClientHeartThread clientHeartThread = this.clientHeartThread;
            if (Objects.isNull(clientHeartThread) || !clientHeartThread.isAlive()) {
                // 客户端心跳线程不存在或已死亡，创建新的心跳线程
                clientHeartThread = this.clientHeartThread = this.config.newClientHeartThread(this);
                if (Objects.nonNull(clientHeartThread)) {
                    // 启动新的心跳线程
                    clientHeartThread.start();
                }
            }

            // 创建并启动新的线程
            myThread = this.myThread = new Thread(this);
            myThread.setName("client-" + this.formatInfo());
            myThread.start();
        }
    }

    /**
     * * 停止客户端监听
     */
    public void stopClient() {
        boolean disconnected = this.isAlive && !cancelled;
        this.isAlive = false;
        if (disconnected) {
            lastError = "控制连接已断开，等待重新注册";
            RuntimeEvents.event("client", "WARN", "连接断开", getListenServerPort(), lastError, "客户端将自动尝试恢复连接");
        }

        Thread myThread = this.myThread;
        if (myThread != null) {
            this.myThread = null;
            myThread.interrupt();
        }

        IClientAdapter<?, ?> clientAdapter = this.clientAdapter;
        if (Objects.nonNull(clientAdapter)) {
            try {
                clientAdapter.close();
            } catch (Exception e) {
                // do nothing
            }
        }
    }

    /**
     * 全部退出
     */
    public synchronized void cancell() {
        if (this.cancelled) {
            return;
        }
        this.cancelled = true;

        this.stopClient();

        IClientHeartThread clientHeartThread;
        if ((clientHeartThread = this.clientHeartThread) != null) {
            this.clientHeartThread = null;
            try {
                clientHeartThread.cancel();
            } catch (Exception e) {
                // do nothing
            }
        }

        IClientAdapter<?, ?> clientAdapter;
        if ((clientAdapter = this.clientAdapter) != null) {
            this.clientAdapter = null;
            try {
                clientAdapter.close();
            } catch (Exception e) {
                // do nothing
            }
        }

        String[] array;
        synchronized (socketPartMap) { array = this.socketPartMap.keySet().toArray(new String[0]); }

        for (String key : array) {
            this.stopSocketPart(key);
        }

    }

    /**
     * 服务端监听的端口
     *
     * @return
     */
    public Integer getListenServerPort() {
        return this.config.getListenServerPort();
    }

    /**
     * 重设目标端口
     *
     * @param destIp
     * @param destPort
     */
    public void setDestIpPort(String destIp, Integer destPort) {
        this.config.setDestIpPort(destIp, destPort);
        targetError = "";
        metadataDirty = true;
        if (isAlive) {
            try { this.clientAdapter.sendClientInfo(); metadataDirty = false; }
            catch (Exception e) { targetError = "目标已保存，客户端信息待同步"; }
        }
    }

    /**
     * 检测是否还活着
     *
     * @return
     */
    public boolean isAlive() {
        return this.isAlive;
    }

    /**
     * 发送心跳测试
     *
     * @throws Exception
     */
    public void sendHeartTest() throws Exception {
        if (metadataDirty) { this.clientAdapter.sendClientInfo(); metadataDirty = false; }
        if (healthSupplier != null) {
            var snapshot = healthSupplier.get();
            if (snapshot != null && snapshot != sentHealth) { this.clientAdapter.sendHealth(snapshot); sentHealth = snapshot; }
        }
        // 无需判空，空指针异常也是异常
        this.clientAdapter.sendHeartTest();
    }

    /**
     * 获取服务端上次心跳测试成功时间
     *
     * @return
     */
    public LocalDateTime obtainServerHeartLastRecvTime() {
        return this.clientAdapter.obtainServerHeartLastRecvTime();
    }

    /**
     * 设置隧道伙伴
     *
     * @param socketPartKey
     * @param socketPart
     */
    public boolean putSocketPart(String socketPartKey, AbsSocketPart socketPart) {
        synchronized (socketPartMap) {
            if (!cancelled) { this.socketPartMap.put(socketPartKey, socketPart); return true; }
        }
        socketPart.cancel();
        return false;
    }

    /**
     * 格式化信息
     *
     * @return
     */
    public String formatInfo() {
        return String.valueOf(this.getListenServerPort());
    }

    public boolean isConnecting() { return queued || attempting.get(); }
    public String getLastError() { return isAlive ? targetError : lastError; }
    public long getLastAttemptAt() { return lastAttemptAt; }
    public long getNextRetryAt() { return nextRetryAt; }
    public long getConnectedAt() { return connectedAt; }
    public long getLastSeenAt() {
        IClientAdapter<?, ?> adapter = clientAdapter;
        return adapter == null ? 0 : adapter.obtainServerHeartLastRecvTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
    private void registrationFailed(String error) {
        lastError = error;
        long now = System.currentTimeMillis();
        if (!error.equals(reportedRegistrationError) || now - reportedRegistrationAt >= 60_000) {
            reportedRegistrationError = error; reportedRegistrationAt = now;
            RuntimeEvents.event("client", "WARN", "注册失败", getListenServerPort(), "端口注册失败，将自动重试", error);
        }
    }
    public void recordTargetError(String error) {
        String previous = targetError; targetError = error;
        if (!error.equals(previous)) RuntimeEvents.event("client", error.isEmpty() ? "INFO" : "WARN", "目标连接", getListenServerPort(),
                error.isEmpty() ? "目标服务连接已恢复" : "连接目标服务失败", error);
    }
    public int getConnectionCount() { return socketPartMap.size(); }

}
