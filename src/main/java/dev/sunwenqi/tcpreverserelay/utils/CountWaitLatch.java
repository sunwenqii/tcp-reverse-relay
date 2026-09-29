package dev.sunwenqi.tcpreverserelay.utils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * <p>
 * 可增 计数 门闩
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-04-14 14:19:35
 */
public class CountWaitLatch {
    private final Sync sync;

    /**
     * count == 0
     */
    public CountWaitLatch() {
        this(0);
    }

    /**
     * @param count >=0
     */
    public CountWaitLatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        this.sync = new Sync(count);
    }

    /**
     * 等待释放
     *
     * @throws InterruptedException
     */
    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(0);
    }

    /**
     * 等待释放
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireSharedNanos(0, unit.toNanos(timeout));
    }

    /**
     * count++
     */
    public void countUp() {
        sync.acquireShared(1);
    }

    /**
     * --count >= 0
     */
    public void countDown() {
        sync.releaseShared(1);
    }

    /**
     * count >= 0
     */
    public long getCount() {
        return sync.getCount();
    }

    @Override
    public String toString() {
        return super.toString() + "[Count = " + sync.getCount() + "]";
    }


    /**
     * Sync类继承自AbstractQueuedSynchronizer，用于实现同步控制。
     * 它通过管理一个内部状态来控制资源的访问，状态的值表示当前剩余的资源数量。
     */
    private static final class Sync extends AbstractQueuedSynchronizer {
        /**
         * 序列化ID，用于序列化和反序列化时确保类的版本一致。
         */
        private static final long serialVersionUID = 4982264981922014374L;

        /**
         * 构造方法，初始化同步状态。
         *
         * @param count 初始资源数量，即同步状态的初始值。
         */
        Sync(int count) {
            setState(count);
        }

        /**
         * 获取当前资源数量，即同步状态的值。
         *
         * @return 当前资源数量。
         */
        int getCount() {
            return getState();
        }

        /**
         * 尝试获取资源的共享方法。
         * 如果请求的资源数量为0且当前资源数量也为0，则成功获取资源；
         * 否则，如果当前资源数量不为0，则尝试更新资源数量，成功则表示获取了资源。
         *
         * @param acquires 请求的资源数量。
         * @return 返回获取的资源数量，如果不能获取资源则返回-1。
         */
        @Override
        protected int tryAcquireShared(int acquires) {
            if (acquires == 0) {
                return (getState() == 0) ? acquires : -1;
            }

            for (; ; ) {
                int c = getState();
                int nextc = c + acquires;
                if (compareAndSetState(c, nextc)) {
                    return acquires;
                }
            }
        }

        /**
         * 尝试释放资源的共享方法。
         * 当前线程在释放资源前会检查当前资源数量是否为0，如果为0则不做任何操作并返回false；
         * 否则，尝试更新资源数量，成功则可能唤醒其他等待资源的线程。
         *
         * @param releases 要释放的资源数量。
         * @return 如果资源数量在释放后变为0，则返回true，表示所有资源已被释放；否则返回false。
         */
        @Override
        protected boolean tryReleaseShared(int releases) {
            // Decrement count; signal when transition to zero
            for (; ; ) {
                int c = getState();
                if (c == 0) {
                    return false;
                }
                int nextc = c - releases;
                if (compareAndSetState(c, nextc)) {
                    return nextc == 0;
                }
            }
        }
    }
}
