package dev.sunwenqi.tcpreverserelay.executor;

import dev.sunwenqi.tcpreverserelay.clientside.adapter.InteractiveSimpleClientAdapter;
import dev.sunwenqi.tcpreverserelay.nio.NioHallows;
import dev.sunwenqi.tcpreverserelay.serverside.client.ClientServiceThread;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ServerListenThread;

import java.util.concurrent.ScheduledFuture;

/**
 * <p>
 * 执行器实现
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-04-08 14:38:23
 */
public interface IExecutor {

    /**
     * 关闭
     *
     * @throws Exception
     */
    void shutdown();

    /**
     * 默认执行方法
     *
     * @param runnable
     */
    void execute(Runnable runnable);

    /**
     * 服务监听线程任务执行器
     * <p>
     * For {@link ServerListenThread}
     *
     * @param runnable
     */
    default void executeServerListenAccept(Runnable runnable) {
        execute(runnable);
    }

    /**
     * 客户端监听线程任务执行器
     * <p>
     * For {@link ClientServiceThread}
     *
     * @param runnable
     */
    default void executeClientServiceAccept(Runnable runnable) {
        execute(runnable);
    }

    /**
     * 客户端消息处理任务执行器
     * <p>
     * For
     * {@link InteractiveSimpleClientAdapter#waitMessage()}
     *
     * @param runnable
     */
    default void executeClientMessageProc(Runnable runnable) {
        execute(runnable);
    }

    /**
     * 隧道线程执行器
     * <p>
     * For {@link com.sunwenqi.tcpreverserelay.api.passway}
     *
     * @param runnable
     */
    default void executePassway(Runnable runnable) {
        execute(runnable);
    }

    /**
     * nio事件任务执行器
     * <p>
     * For {@link NioHallows#run()}
     *
     * @param runnable
     */
    default void executeNioAction(Runnable runnable) {
        execute(runnable);
    }

    /**
     * 心跳检测定时循环任务执行
     *
     * @param runnable
     * @param delaySeconds
     */
    ScheduledFuture<?> scheduledClientHeart(Runnable runnable, long delaySeconds);

    /**
     * 服务监听清理无效socket对
     *
     * @param runnable
     * @param delaySeconds
     * @return
     */
    ScheduledFuture<?> scheduledClearInvalidSocketPart(Runnable runnable, long delaySeconds);

}
