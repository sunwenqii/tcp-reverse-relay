package dev.sunwenqi.tcpreverserelay.clientside.config;

import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.clientside.ClientControlThread;
import dev.sunwenqi.tcpreverserelay.clientside.adapter.IClientAdapter;
import dev.sunwenqi.tcpreverserelay.clientside.heart.IClientHeartThread;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.spi.SelectorProvider;

/**
 * <p>
 * 客户端配置接口
 * </p>
 *
 * @param <R> 通道读取的类型
 * @param <W> 通道写入的类型
 * @author Sunwenqi
 * @since 2025-01-08 16:30:04
 */
public interface IClientConfig<R, W> {
    default String getClientId() { return ""; }
    default String getClientName() { return "client"; }

    /**
     * 获取服务端IP
     *
     * @return
     */
    String getClientServiceIp();

    /**
     * 获取服务端端口
     *
     * @return
     */
    Integer getClientServicePort();

    /**
     * 对应的监听端口
     *
     * @return
     */
    Integer getListenServerPort();

    /**
     * 目标IP
     *
     * @return
     */
    String getDestIp();

    /**
     * 目标端口
     *
     * @return
     */
    Integer getDestPort();

    /**
     * 设置目标IP
     *
     * @param destIp
     */
    void setDestIpPort(String destIp, Integer destPort);

    /**
     * 新建心跳测试线程
     *
     * @param clientControlThread
     * @return
     */
    IClientHeartThread newClientHeartThread(ClientControlThread clientControlThread);

    /**
     * 新建适配器
     *
     * @param clientControlThread
     * @return
     */
    IClientAdapter<R, W> newCreateControlAdapter(ClientControlThread clientControlThread);

    /**
     * 新建与服务端的交互线程
     *
     * @return
     */
    SocketChannel<? extends R, ? super W> newClientChannel();

    /**
     * 创建新的socketPart
     *
     * @return
     */
    AbsSocketPart newSocketPart(ClientControlThread clientControlThread);

    /**
     * 创建目标端口
     *
     * @return
     * @throws Exception
     */
    default Socket newDestSocket() throws Exception {
        // 打开一个新的SocketChannel
        java.nio.channels.SocketChannel openSocketChannel = SelectorProvider.provider().openSocketChannel();
        // 使用SocketChannel连接到目标IP和端口
        try {
            String host;
            int port;
            synchronized (this) { host = getDestIp(); port = getDestPort(); }
            openSocketChannel.socket().connect(new InetSocketAddress(host, port), 5000);
        } catch (Exception e) {
            openSocketChannel.close();
            throw e;
        }
        // 返回SocketChannel对应的Socket对象
        return openSocketChannel.socket();
    }
}
