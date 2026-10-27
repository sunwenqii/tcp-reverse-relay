package dev.sunwenqi.tcpreverserelay.serverside.listen;

import dev.sunwenqi.tcpreverserelay.model.interactive.ClientControlModel;
import dev.sunwenqi.tcpreverserelay.model.interactive.ClientHealthModel;
import dev.sunwenqi.tcpreverserelay.serverside.listen.control.IControlSocket;

/**
 * <p>
 * 端口监听服务接口
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-07-01 13:46:20
 */
public interface IServerListen {
    default void updateClientInfo(IControlSocket control,
            ClientControlModel info) {}

    default void updateHealth(IControlSocket control, ClientHealthModel report) {}

    /**
     * 格式化信息
     *
     * @return
     */
    String formatInfo();

    /**
     * 控制端口通知关闭
     *
     * @param controlSocket
     */
    void controlCloseNotice(IControlSocket controlSocket);

}
