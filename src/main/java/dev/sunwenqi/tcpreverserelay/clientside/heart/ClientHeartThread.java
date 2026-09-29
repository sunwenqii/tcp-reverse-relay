package dev.sunwenqi.tcpreverserelay.clientside.heart;

import dev.sunwenqi.tcpreverserelay.clientside.ClientControlThread;
import dev.sunwenqi.tcpreverserelay.executor.RelayExecutor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/**
 * <p>
 * 心跳检测线程
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-04-25 17:53:44
 */
@Slf4j
public class ClientHeartThread implements IClientHeartThread, Runnable {
    /**
     * 控制客户端的线程，用于管理客户端的生命周期和心跳
     */
    private final ClientControlThread clientControlThread;

    /**
     * 表示客户端的运行状态，用于在客户端启动和停止时进行标记
     */
    private volatile boolean isAlive = false;

    /**
     * 心跳间隔时间（秒），用于设置客户端发送心跳的频率
     */
    @Setter
    @Getter
    private long heartIntervalSeconds = 10L;

    /**
     * 尝试接收信息的次数，用于在客户端尝试接收信息时的重试机制
     */
    @Setter
    @Getter
    private int tryRecipientCount = 10;

    /**
     * 服务器心跳最大遗漏时长（秒），用于判断服务器心跳是否超时
     */
    @Setter
    @Getter
    private long serverHeartMaxMissDurationSeconds = 25L;

    /**
     * 定时任务的调度信息，用于管理心跳等定时任务
     */
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * 失败计数，用于记录客户端操作失败的次数，以便进行相应的处理
     */
    private int failCount = 0;

    public ClientHeartThread(ClientControlThread clientControlThread) {
        this.clientControlThread = clientControlThread;
    }

    /**
     * 检查服务端是否心跳超时
     *
     * @param clientControlThread
     * @param timeoutSeconds
     * @return
     */
    private boolean lastServerHeartEffective(ClientControlThread clientControlThread, Long timeoutSeconds) {
        return Duration.between(clientControlThread.obtainServerHeartLastRecvTime(), LocalDateTime.now())
                .get(ChronoUnit.SECONDS) < timeoutSeconds;
    }

    @Override
    public void run() {
        // 检查客户端控制线程的状态，决定是否需要取消当前操作
        ClientControlThread clientControlThread = this.clientControlThread;
        if (clientControlThread.isCancelled() || !this.isAlive()) {
            this.cancel();
            return;
        }

        // 准备发送心跳数据到服务器，先记录日志
        log.debug("send client heart data to {}", clientControlThread.getListenServerPort());
        try {
            // 如果服务端心跳超时则直接判定为失败，否则进行心跳检查
            if (this.lastServerHeartEffective(clientControlThread, this.serverHeartMaxMissDurationSeconds)) {
                clientControlThread.sendHeartTest();
                this.failCount = 0;
                return;
            }
        } catch (Exception e) {
            // 心跳过程中出现异常，记录日志并停止客户端操作
            log.warn("{} 心跳异常", clientControlThread.getListenServerPort());
            clientControlThread.stopClient();
        }

        // 再次检查客户端控制线程的状态
        if (!this.isAlive) {
            return;
        }

        // 记录失败次数
        this.failCount++;

        boolean createControl = false, logFlag = true;
        try {
            // 尝试重新建立控制连接
            createControl = clientControlThread.createControl();
        } catch (Exception reClientException) {
            // 重新建立连接失败，记录日志
            log.warn("重新建立连接" + clientControlThread.getListenServerPort() + "失败第 " + this.failCount + " 次",
                    reClientException);
            logFlag = false;
        }

        // 如果连接重新建立成功，重置失败次数并记录日志
        if (createControl) {
            log.info("重新建立连接 {} 成功，在第 {} 次", clientControlThread.getListenServerPort(), this.failCount);
            this.failCount = 0;
            return;
        }

        // 如果没有成功重新建立连接，且之前没有记录过失败日志，则记录失败日志
        if (logFlag) {
            log.warn("重新建立连接" + clientControlThread.getListenServerPort() + "失败第 " + this.failCount + " 次");
        }

        // 如果失败次数超过最大尝试次数，记录错误日志并取消客户端操作
        if (this.tryRecipientCount > 0 && this.failCount >= this.tryRecipientCount) {
            log.error("尝试重新连接 {} 超过最大次数，关闭客户端", clientControlThread.getListenServerPort());
            clientControlThread.cancell();
            this.cancel();
        }
    }

    @Override
    public synchronized void start() {
        this.isAlive = true;

        ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (Objects.isNull(scheduledFuture) || scheduledFuture.isCancelled()) {
            this.failCount = 0;
            this.scheduledFuture = RelayExecutor.scheduledClientHeart(this, this.heartIntervalSeconds);
        }
    }

    @Override
    public void cancel() {
        if (!this.isAlive) {
            return;
        }
        this.isAlive = false;

        ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (Objects.nonNull(scheduledFuture) && !scheduledFuture.isCancelled()) {
            this.scheduledFuture = null;
            scheduledFuture.cancel(false);
        }
    }

    @Override
    public boolean isAlive() {
        return this.isAlive;
    }
}
