package dev.sunwenqi.tcpreverserelay.serverside.client.process;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.RelayResultModel;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import dev.sunwenqi.tcpreverserelay.model.enumeration.RelayResultEnum;
import dev.sunwenqi.tcpreverserelay.model.interactive.ClientControlModel;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ListenServerControl;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ServerListenThread;

/**
 * <p>
 * 请求建立控制器处理方法
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:48:43
 */
public class ClientControlProcess implements IProcess {

    public static final ClientControlProcess INSTANCE = new ClientControlProcess();

    @Override
    public boolean wouldProc(InteractiveModel recvInteractiveModel) {
        InteractiveTypeEnum interactiveTypeEnum = InteractiveTypeEnum.getEnumByName(
                recvInteractiveModel.getInteractiveType());
        return InteractiveTypeEnum.CLIENT_CONTROL.equals(interactiveTypeEnum);
    }

    @Override
    public boolean processMethod(SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel,
                                 InteractiveModel recvInteractiveModel) throws Exception {
        ClientControlModel clientControlModel = recvInteractiveModel.getData().toJavaObject(ClientControlModel.class);
        ServerListenThread serverListenThread = ListenServerControl.get(clientControlModel.getListenPort());

        if (serverListenThread == null) {
            socketChannel.writeAndFlush(
                    InteractiveModel.of(recvInteractiveModel.getInteractiveSeq(), InteractiveTypeEnum.COMMON_REPLY,
                            RelayResultEnum.NO_HAS_SERVER_LISTEN.toResultModel()));
            return false;
        }

        socketChannel.writeAndFlush(
                InteractiveModel.of(recvInteractiveModel.getInteractiveSeq(), InteractiveTypeEnum.COMMON_REPLY,
                        RelayResultModel.ofSuccess()));

        serverListenThread.setControlSocket(socketChannel.getSocket());
        return true;
    }

}
