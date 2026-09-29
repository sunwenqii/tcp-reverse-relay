package dev.sunwenqi.tcpreverserelay.serverside.client.config;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.serverside.client.adapter.IClientServiceAdapter;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * <p>
 * 客户端服务配置
 * </p>
 *
 * @param <R> 交互通道读取的类
 * @param <W> 交互通道可写的类
 * @author Sunwenqi
 * @since 2025-01-08 16:43:17
 */
public interface IClientServiceConfig<R, W> {

    /**
     * 监听端口
     */
    Integer getListenPort();

    /**
     * 创建监听端口
     */
    ServerSocket createServerSocket() throws Exception;

    /**
     * 客户端适配器
     */
    IClientServiceAdapter getClientServiceAdapter();

    /**
     * 交互通道
     */
    SocketChannel<? extends R, ? super W> newSocketChannel(Socket listenSocket) throws Exception;

    /**
     * 字符集
     */
    Charset getCharset();

}
