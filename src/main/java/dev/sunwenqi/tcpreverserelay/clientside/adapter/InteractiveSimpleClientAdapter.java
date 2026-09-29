package dev.sunwenqi.tcpreverserelay.clientside.adapter;

import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.clientside.ClientControlThread;
import dev.sunwenqi.tcpreverserelay.clientside.config.IClientConfig;
import dev.sunwenqi.tcpreverserelay.clientside.handler.IClientHandler;
import dev.sunwenqi.tcpreverserelay.executor.RelayExecutor;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.RelayResultModel;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import dev.sunwenqi.tcpreverserelay.model.enumeration.RelayResultEnum;
import dev.sunwenqi.tcpreverserelay.model.interactive.ClientControlModel;
import dev.sunwenqi.tcpreverserelay.model.interactive.ServerWaitModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 基于InteractiveModel的客户端适配器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:24:07
 */
@Slf4j
public class InteractiveSimpleClientAdapter implements IClientAdapter<InteractiveModel, InteractiveModel> {

    /**
     * 所属的客户端线程
     */
    private final ClientControlThread clientControlThread;
    /**
     * 客户端设置
     */
    private final IClientConfig<InteractiveModel, InteractiveModel> config;
    /**
     * 客户端消息接收处理链
     */
    protected List<IClientHandler<? super InteractiveModel, ? extends InteractiveModel>> messageHandlerList = new LinkedList<>();
    /**
     * 适配器通道
     */
    private SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel;
    /**
     * 记录服务器最后一次接收心跳的时间
     */
    private LocalDateTime serverHeartLastRecvTime = LocalDateTime.now();

    public InteractiveSimpleClientAdapter(ClientControlThread clientControlThread,
                                          IClientConfig<InteractiveModel, InteractiveModel> clientConfig) {
        this.clientControlThread = clientControlThread;
        this.config = clientConfig;
    }

    /**
     * 创建客户端通道
     *
     * @return
     */
    protected SocketChannel<? extends InteractiveModel, ? super InteractiveModel> newClientChannel() {
        return this.config.newClientChannel();
    }

    /**
     * 穿透目标socket
     *
     * @return
     * @throws Exception
     */
    protected Socket newDestSocket() throws Exception {
        return this.config.newDestSocket();
    }

    /**
     * 向服务端和暴露目标创建socketPart
     *
     * @return
     */
    protected AbsSocketPart newSocketPart() {
        return this.config.newSocketPart(this.clientControlThread);
    }

    @Override
    public boolean createControlChannel() throws Exception {
        SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel = this.newClientChannel();
        if (socketChannel == null) {
            log.error("向服务端[{}:{}]建立控制通道失败", this.config.getClientServiceIp(),
                    this.config.getClientServicePort());
            return false;
        }
        boolean connected = false;
        try {
            socketChannel.getSocket().setSoTimeout(5000);
            InteractiveModel interactiveModel = InteractiveModel.of(InteractiveTypeEnum.CLIENT_CONTROL,
                    new ClientControlModel(this.config.getListenServerPort()));
            socketChannel.writeAndFlush(interactiveModel);
            InteractiveModel recv = socketChannel.read();
            log.debug("建立控制端口回复：{}", recv.getInteractiveType());
            RelayResultModel javaObject = recv.getData().toJavaObject(RelayResultModel.class);
            if (StringUtils.equals(RelayResultEnum.SUCCESS.getCode(), javaObject.getRetCod())) {
                socketChannel.getSocket().setSoTimeout(0);
                this.socketChannel = socketChannel;
                this.resetServerHeartLastRecvTime();
                connected = true;
                return true;
            }
            return false;
        } finally {
            if (!connected) {
                try {
                    socketChannel.closeSocket();
                } catch (IOException ignored) {
                    // 失败的控制连接不再保留。
                }
            }
        }
    }

    /**
     * 建立连接
     *
     * @param serverWaitModel
     */
    @Override
    public boolean clientConnect(ServerWaitModel serverWaitModel) {
        // 首先向暴露目标建立socket
        Socket destSocket;
        try {
            destSocket = this.newDestSocket();
        } catch (Exception e) {
            log.error("向目标建立连接失败 {}:{}", this.config.getDestIp(), this.config.getDestPort());
            return false;
        }

        SocketChannel<? extends InteractiveModel, ? super InteractiveModel> passwayClientChannel = null;
        try {
            // 向服务端请求建立隧道
            passwayClientChannel = this.newClientChannel();
            passwayClientChannel.getSocket().setSoTimeout(5000);

            InteractiveModel model = InteractiveModel.of(InteractiveTypeEnum.CLIENT_CONNECT,
                    new ServerWaitModel(serverWaitModel.getSocketPartKey()));

            passwayClientChannel.writeAndFlush(model);

            InteractiveModel recv = passwayClientChannel.read();
            log.debug("建立隧道回复：{}", recv.getInteractiveType());

            RelayResultModel javaObject = recv.getData().toJavaObject(RelayResultModel.class);

            if (!StringUtils.equals(RelayResultEnum.SUCCESS.getCode(), javaObject.getRetCod())) {
                throw new RuntimeException("绑定失败");
            }
            passwayClientChannel.getSocket().setSoTimeout(0);

        } catch (Exception e) {
            log.error(
                    "打通隧道发生异常 " + this.config.getClientServiceIp() + ":" + this.config.getClientServicePort() +
                            "<->" + this.config.getDestIp() + ":" + this.config.getDestPort(), e);
            try {
                destSocket.close();
            } catch (IOException e1) {
                // do nothing
            }

            if (passwayClientChannel != null) {
                try {
                    passwayClientChannel.closeSocket();
                } catch (IOException e1) {
                    // do nothing
                }
            }
            return false;
        }

        // 将两个socket建立伙伴关系
        AbsSocketPart socketPart = this.newSocketPart();
        socketPart.setSocketPartKey(serverWaitModel.getSocketPartKey());
        socketPart.setSendSocket(passwayClientChannel.getSocket());
        socketPart.setRecvSocket(destSocket);
        // 尝试打通隧道
        boolean createPassWay = socketPart.createPassWay();
        if (!createPassWay) {
            socketPart.cancel();
            return false;
        }

        // 将socket伙伴放入客户端线程进行统一管理
        this.clientControlThread.putSocketPart(serverWaitModel.getSocketPartKey(), socketPart);
        return true;
    }

    @Override
    public void waitMessage() throws Exception {
        InteractiveModel read = this.socketChannel.read();

        // 只要有有效信息推送过来，则重置心跳时间
        this.resetServerHeartLastRecvTime();

        // 执行客户端消息处理程序
        RelayExecutor.executeClientMessageProc(() -> this.procMethod(read));
    }

    /**
     * 消息处理方法
     *
     * @param recvInteractiveModel
     */
    protected void procMethod(InteractiveModel recvInteractiveModel) {
        log.debug("接收到新的指令: {}", recvInteractiveModel.getInteractiveType());
        try {
            // 初始化进行标志为false，用于标记是否已处理接收到的交互模型
            boolean proceedFlag = false;

            // 遍历消息处理器列表，尝试处理接收到的交互模型
            for (IClientHandler<? super InteractiveModel, ? extends InteractiveModel> handler : this.messageHandlerList) {
                // 调用当前处理器的proc方法处理交互模型，如果处理成功，将proceedFlag设置为true并终止循环
                proceedFlag = handler.proc(recvInteractiveModel, this);
                if (proceedFlag) {
                    break;
                }
            }

            // 如果没有处理器成功处理接收到的交互模型
            if (!proceedFlag) {
                // 记录警告日志，表示没有识别的交互类型
                log.warn("无处理方法的信息：[{}]", recvInteractiveModel.getInteractiveType());
                // 构建一个通用回复的交互模型，表示未知交互类型，并发送给客户端
                InteractiveModel result = InteractiveModel.of(recvInteractiveModel.getInteractiveSeq(),
                        InteractiveTypeEnum.COMMON_REPLY, RelayResultEnum.UNKNOWN_INTERACTIVE_TYPE.toResultModel());
                this.getSocketChannel().writeAndFlush(result);
            }

        } catch (Exception e) {
            // 只记录，其他的交给心跳之类的去把控
            log.error("读取或写入异常", e);
        }
    }

    /**
     * 获取当前客户端的Socket通道
     *
     * @return 返回当前客户端的Socket通道实例
     */
    @Override
    public SocketChannel<? extends InteractiveModel, ? super InteractiveModel> getSocketChannel() {
        return this.socketChannel;
    }

    /**
     * 添加消息处理器
     *
     * @param handler 要添加的消息处理器实例
     * @return 返回InteractiveSimpleClientAdapter实例，支持链式调用
     */
    public InteractiveSimpleClientAdapter addMessageHandler(
            IClientHandler<? super InteractiveModel, ? extends InteractiveModel> handler) {
        this.messageHandlerList.add(handler);
        return this;
    }

    /**
     * 关闭客户端连接
     *
     * @throws Exception 如果关闭过程中发生错误，则抛出异常
     */
    @Override
    public void close() throws Exception {
        SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel = this.socketChannel;
        if (Objects.nonNull(socketChannel)) {
            this.socketChannel = null;
            socketChannel.closeSocket();
        }
    }

    /**
     * 发送心跳测试消息到服务器
     *
     * @throws Exception 如果发送过程中发生错误，则抛出异常
     */
    @Override
    public void sendHeartTest() throws Exception {
        InteractiveModel interactiveModel = InteractiveModel.of(InteractiveTypeEnum.HEART_TEST, null);
        this.socketChannel.writeAndFlush(interactiveModel);
    }

    /**
     * 获取服务器最后一次心跳接收时间
     *
     * @return 返回服务器最后一次心跳接收的本地时间
     */
    @Override
    public LocalDateTime obtainServerHeartLastRecvTime() {
        return this.serverHeartLastRecvTime;
    }

    /**
     * 重置服务器最后一次心跳接收时间
     *
     * @param time 新的服务器心跳接收时间
     * @return 返回重置前的服务器心跳接收时间
     */
    @Override
    public LocalDateTime resetServerHeartLastRecvTime(LocalDateTime time) {
        LocalDateTime tempTime = this.serverHeartLastRecvTime;
        this.serverHeartLastRecvTime = time;
        return tempTime;
    }
}
