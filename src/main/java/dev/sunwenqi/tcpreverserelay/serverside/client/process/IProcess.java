package dev.sunwenqi.tcpreverserelay.serverside.client.process;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;

/**
 * <p>
 * 处理方法接口
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:49:06
 */
public interface IProcess {

    /**
     * 判断是否是由这个处理
     */
    boolean wouldProc(InteractiveModel recvInteractiveModel);

    /**
     * 处理方法，需要回复信息的，自己使用socketChannel回复
     *
     * @return 是否保持socket开启状态
     */
    boolean processMethod(SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel,
                          InteractiveModel recvInteractiveModel) throws Exception;

}
