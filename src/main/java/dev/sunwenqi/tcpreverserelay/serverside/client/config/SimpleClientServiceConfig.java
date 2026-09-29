package dev.sunwenqi.tcpreverserelay.serverside.client.config;

import dev.sunwenqi.tcpreverserelay.channel.InteractiveChannel;
import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.serverside.client.adapter.DefaultReadAheadPassValueAdapter;
import dev.sunwenqi.tcpreverserelay.serverside.client.adapter.IClientServiceAdapter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * <p>
 * 简单交互的客户端服务配置
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:45:47
 */
@NoArgsConstructor
public class SimpleClientServiceConfig implements IClientServiceConfig<InteractiveModel, InteractiveModel> {

    private Integer listenPort;
    @Setter
    private IClientServiceAdapter clientServiceAdapter = new DefaultReadAheadPassValueAdapter(this);
    @Setter
    private Charset charset = StandardCharsets.UTF_8;

    public SimpleClientServiceConfig(Integer listenPort) {
        this.listenPort = listenPort;
    }

    @Override
    public Integer getListenPort() {
        return this.listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    @Override
    public ServerSocket createServerSocket() throws Exception {
        ServerSocketChannel openServerSocketChannel = SelectorProvider.provider().openServerSocketChannel();
        openServerSocketChannel.bind(new InetSocketAddress(this.getListenPort()));
        return openServerSocketChannel.socket();
    }

    @Override
    public IClientServiceAdapter getClientServiceAdapter() {
        return this.clientServiceAdapter;
    }

    @Override
    public SocketChannel<? extends InteractiveModel, ? super InteractiveModel> newSocketChannel(Socket listenSocket)
            throws Exception {
        InteractiveChannel channel = new InteractiveChannel();
        channel.setSocket(listenSocket);
        channel.setCharset(this.charset);
        return channel;
    }

    @Override
    public Charset getCharset() {
        return this.charset;
    }

}
