package dev.sunwenqi.tcpreverserelay.serverside.client.handler;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.common.Optional;
import dev.sunwenqi.tcpreverserelay.serverside.client.adapter.PassValueNextEnum;

/**
 * <p>
 * 传值方式客户端是配置的处理接口
 * </p>
 *
 * @param <R>
 * @param <W>
 * @author Sunwenqi
 * @since 2025-01-08 16:47:40
 */
public interface IPassValueHandler<R, W> {

    /**
     * 处理方法
     *
     * @param socketChannel 交互通道
     * @param optional      可以重设值
     * @return
     */
    PassValueNextEnum proc(SocketChannel<? extends R, ? super W> socketChannel, Optional<? extends R> optional);

}
