package dev.sunwenqi.tcpreverserelay.nio;

import java.nio.channels.SelectionKey;

/**
 * <p>
 * nio 执行器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-04-12 17:51:37
 */
@FunctionalInterface
public interface INioProcessor {

    /**
     * 需要执行的方法
     *
     * @param key
     */
    void process(SelectionKey key);

}
