package dev.sunwenqi.tcpreverserelay.nio;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

/**
 * <p>
 * 执行器暂存
 * </p>
 * <p>
 * 该类用于暂存执行器相关的信息和状态，包括通道、感兴趣的事件操作和执行器实例
 * 主要作用是在NIO事件处理过程中，作为事件处理的上下文，传递和存储事件处理所需的信息
 *
 * @author Sunwenqi
 * @since 2025-04-13 09:53:54
 */
@Data
@AllArgsConstructor(staticName = "of")
public class ProcesserHolder {

    /**
     * 通道
     * 存储NIO通信的通道，用于后续的数据传输和通道管理
     */
    private SelectableChannel channel;

    /**
     * 感兴趣的事件操作
     * 存储当前执行器感兴趣的NIO事件类型，如读、写等，用于事件处理和重新注册
     */
    private int interestOps;

    /**
     * 执行器实例
     * 存储具体的NIO事件处理逻辑实现，用于处理发生的NIO事件
     */
    private INioProcessor processor;

    /**
     * 执行事件的任务
     * <p>
     * 该方法负责调用执行器处理发生的NIO事件，并根据处理结果决定是否需要重新注册通道
     * 如果不需要重新注册，将释放通道资源
     *
     * @param key NIO事件的SelectionKey，包含发生的事件和对应的通道信息
     * @author Sunwenqi
     * @since 2025-04-26 16:35:36
     */
    public void process(SelectionKey key) {
        this.processor.process(key);

        if (!NioHallows.reRegisterByKey(key, this.interestOps)) {
            NioHallows.release(this.channel);
        }
    }
}
