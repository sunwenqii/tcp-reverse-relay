package dev.sunwenqi.tcpreverserelay.serverside.client.process;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.common.CommonFormat;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.RelayResultModel;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import dev.sunwenqi.tcpreverserelay.model.enumeration.RelayResultEnum;
import dev.sunwenqi.tcpreverserelay.model.interactive.ClientConnectModel;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ListenServerControl;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ServerListenThread;

/**
 * <p>
 * 请求建立隧道处理器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:48:25
 */
public class ClientConnectProcess implements IProcess {

    public static final ClientConnectProcess INSTANCE = new ClientConnectProcess();

    @Override
    public boolean wouldProc(InteractiveModel recvInteractiveModel) {
        InteractiveTypeEnum interactiveTypeEnum = InteractiveTypeEnum.getEnumByName(
                recvInteractiveModel.getInteractiveType());
        return InteractiveTypeEnum.CLIENT_CONNECT.equals(interactiveTypeEnum);
    }

    @Override
    public boolean processMethod(SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel,
                                 InteractiveModel recvInteractiveModel) throws Exception {
        ClientConnectModel clientConnectModel = recvInteractiveModel.getData().toJavaObject(ClientConnectModel.class);
        Integer listenPort = CommonFormat.getSocketPortByPartKey(clientConnectModel.getSocketPartKey());

        ServerListenThread serverListenThread = ListenServerControl.get(listenPort);

        if (serverListenThread == null) {
            socketChannel.writeAndFlush(
                    InteractiveModel.of(recvInteractiveModel.getInteractiveSeq(), InteractiveTypeEnum.COMMON_REPLY,
                            RelayResultEnum.NO_HAS_SERVER_LISTEN.toResultModel()));
            return false;
        }

        // 回复设置成功，如果doSetPartClient没有找到对应的搭档，则直接按关闭处理
        socketChannel.writeAndFlush(
                InteractiveModel.of(recvInteractiveModel.getInteractiveSeq(), InteractiveTypeEnum.COMMON_REPLY,
                        RelayResultModel.ofSuccess()));

        // 若设置成功，则上层无需关闭
        // 若设置失败，则由上层关闭
        return serverListenThread.doSetPartClient(clientConnectModel.getSocketPartKey(), socketChannel.getSocket());
    }

}
