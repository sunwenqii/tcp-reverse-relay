package dev.sunwenqi.tcpreverserelay.clientside;

import dev.sunwenqi.tcpreverserelay.api.IBelongControl;
import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.clientside.adapter.IClientAdapter;
import dev.sunwenqi.tcpreverserelay.clientside.config.IClientConfig;
import dev.sunwenqi.tcpreverserelay.clientside.heart.IClientHeartThread;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
        this.stopClient();

        if (this.clientAdapter == null) {
            this.clientAdapter = this.config.newCreateControlAdapter(this);
        }

        boolean flag = this.clientAdapter.createControlChannel();

        if (!flag) {
            return false;
        }

        this.start();
        return true;
    }

    /**
     * 首次连接失败时也启动现有的心跳重连任务。
     */
    public void startWithReconnect() {
        try {
            if (this.createControl()) {
                return;
            }
        } catch (Exception e) {
            log.warn("首次连接服务端失败，稍后重试：{}", this.config.getListenServerPort(), e);
        }
        if (this.clientHeartThread == null) {
            this.clientHeartThread = this.config.newClientHeartThread(this);
        }
        this.clientHeartThread.start();
    }

    @Override
    public void run() {
        while (this.isAlive) {
            try {
                // 使用适配器代理执行
                this.clientAdapter.waitMessage();
            } catch (Exception e) {
                log.warn("client control [{}] to server is exception,will stopClient",
                        this.config.getListenServerPort());
                this.stopClient();
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
        this.cancelled = false;

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
        this.isAlive = false;

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
    public void cancell() {
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

        String[] array = this.socketPartMap.keySet().toArray(new String[0]);

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
    public void putSocketPart(String socketPartKey, AbsSocketPart socketPart) {
        this.socketPartMap.put(socketPartKey, socketPart);
    }

    /**
     * 格式化信息
     *
     * @return
     */
    public String formatInfo() {
        return String.valueOf(this.getListenServerPort());
    }

}
