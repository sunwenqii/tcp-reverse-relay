package dev.sunwenqi.tcpreverserelay.serverside.listen.recv;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;

/**
 * <p>
 * 接收处理器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-04-15 11:13:20
 */
public interface IRecvHandler<R, W> {

    /**
     * 处理方法
     *
     * @param model
     * @param channel
     * @return
     * @throws Exception
     */
    boolean proc(R model, SocketChannel<? extends R, ? super W> channel) throws Exception;

}
