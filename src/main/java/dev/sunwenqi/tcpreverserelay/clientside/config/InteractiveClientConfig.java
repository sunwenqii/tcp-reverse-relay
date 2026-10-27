package dev.sunwenqi.tcpreverserelay.clientside.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.api.socketpart.SimpleSocketPart;
import dev.sunwenqi.tcpreverserelay.channel.InteractiveChannel;
import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.clientside.ClientControlThread;
import dev.sunwenqi.tcpreverserelay.clientside.adapter.IClientAdapter;
import dev.sunwenqi.tcpreverserelay.clientside.adapter.InteractiveSimpleClientAdapter;
import dev.sunwenqi.tcpreverserelay.clientside.handler.CommonReplyHandler;
import dev.sunwenqi.tcpreverserelay.clientside.handler.ServerHeartHandler;
import dev.sunwenqi.tcpreverserelay.clientside.handler.ServerWaitClientHandler;
import dev.sunwenqi.tcpreverserelay.clientside.heart.ClientHeartThread;
import dev.sunwenqi.tcpreverserelay.clientside.heart.IClientHeartThread;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * 简单的以InteractiveModel为交互模型的配置
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:30:53
 */
@Slf4j
@Data
public class InteractiveClientConfig implements IClientConfig<InteractiveModel, InteractiveModel> {
    private String clientId;
    private String clientName;

    /**
     * 客户端服务IP地址，用于指定客户端请求的目标服务器IP
     */
    private String clientServiceIp;

    /**
     * 客户端服务端口，用于指定客户端请求的目标服务器端口
     */
    private Integer clientServicePort;

    /**
     * 监听服务器端口，用于当前服务监听外部请求的端口
     */
    private Integer listenServerPort;

    /**
     * 目标IP地址，用于指定数据传输的目标IP
     */
    private volatile String destIp;

    /**
     * 目标端口，用于指定数据传输的目标端口
     */
    private volatile Integer destPort;

    /**
     * 字符集，用于数据传输过程中的编码解码，默认使用UTF-8
     */
    private Charset charset = StandardCharsets.UTF_8;

    /**
     * 流缓存大小，用于设置数据流读写缓存区的大小，默认为8196字节
     */
    private int streamCacheSize = 8196;

    /**
     * 心跳检测间隔（s）
     */
    private long heartIntervalSeconds = 10L;
    /**
     * 尝试重连次数，若超过则中断连接
     */
    private int tryRecipientCount = 10;

    @Override
    public synchronized void setDestIpPort(String destIp, Integer destPort) {
        this.destIp = destIp;
        this.destPort = destPort;
    }

    @Override
    public IClientHeartThread newClientHeartThread(ClientControlThread clientControlThread) {
        ClientHeartThread clientHeartThread = new ClientHeartThread(clientControlThread);
        clientHeartThread.setHeartIntervalSeconds(this.heartIntervalSeconds);
        clientHeartThread.setTryRecipientCount(this.tryRecipientCount);
        return clientHeartThread;
    }

    @Override
    public IClientAdapter<InteractiveModel, InteractiveModel> newCreateControlAdapter(ClientControlThread clientControlThread) {
        InteractiveSimpleClientAdapter simpleClientAdapter = new InteractiveSimpleClientAdapter(clientControlThread, this);
        simpleClientAdapter.addMessageHandler(CommonReplyHandler.INSTANCE);
        simpleClientAdapter.addMessageHandler(ServerHeartHandler.INSTANCE);
        simpleClientAdapter.addMessageHandler(ServerWaitClientHandler.INSTANCE);
        return simpleClientAdapter;
    }

    @Override
    public SocketChannel<? extends InteractiveModel, ? super InteractiveModel> newClientChannel() {
        InteractiveChannel interactiveChannel = new InteractiveChannel();
        connectService(interactiveChannel);

        interactiveChannel.setCharset(this.charset);

        return interactiveChannel;
    }

    /** Every service socket retains a channel, so the data connection can switch to nonblocking I/O. */
    protected void connectService(SocketChannel<?, ?> destination) {
        java.nio.channels.SocketChannel channel = null;
        try {
            channel = java.nio.channels.SocketChannel.open();
            Socket socket = channel.socket();
            socket.connect(new InetSocketAddress(getClientServiceIp(), getClientServicePort()), 5000);
            destination.setSocket(socket);
        } catch (IOException e) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) {}
            throw new java.io.UncheckedIOException("无法连接服务端", e);
        }
    }

    @Override
    public AbsSocketPart newSocketPart(ClientControlThread clientControlThread) {
        SimpleSocketPart simpleSocketPart = new SimpleSocketPart(clientControlThread);
        simpleSocketPart.setStreamCacheSize(this.getStreamCacheSize());
        return simpleSocketPart;
    }
}
