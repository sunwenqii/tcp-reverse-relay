package dev.sunwenqi.tcpreverserelay.executor;

import java.util.concurrent.*;

/**
 * <p>
 * 线程执行器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-04-09 12:46:36
 */
public class SimpleExecutor implements IExecutor {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    @Override
    public void shutdown() {
        this.executor.shutdownNow();
        this.scheduledExecutor.shutdownNow();
    }

    @Override
    public void execute(Runnable runnable) {
        this.executor.execute(runnable);
    }

    @Override
    public ScheduledFuture<?> scheduledClientHeart(Runnable runnable, long delaySeconds) {
        return this.scheduledExecutor.scheduleWithFixedDelay(runnable, delaySeconds, delaySeconds, TimeUnit.SECONDS);
    }

    @Override
    public ScheduledFuture<?> scheduledClearInvalidSocketPart(Runnable runnable, long delaySeconds) {
        return this.scheduledExecutor.scheduleWithFixedDelay(runnable, delaySeconds, delaySeconds, TimeUnit.SECONDS);
    }

}
