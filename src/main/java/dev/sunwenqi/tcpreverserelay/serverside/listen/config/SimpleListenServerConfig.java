package dev.sunwenqi.tcpreverserelay.serverside.listen.config;

import com.alibaba.fastjson.JSONObject;
import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.api.socketpart.SimpleSocketPart;
import dev.sunwenqi.tcpreverserelay.channel.InteractiveChannel;
import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ServerListenThread;
import dev.sunwenqi.tcpreverserelay.serverside.listen.clear.ClearInvalidSocketPartThread;
import dev.sunwenqi.tcpreverserelay.serverside.listen.clear.IClearInvalidSocketPartThread;
import dev.sunwenqi.tcpreverserelay.serverside.listen.control.ControlSocket;
import dev.sunwenqi.tcpreverserelay.serverside.listen.control.IControlSocket;
import dev.sunwenqi.tcpreverserelay.serverside.listen.recv.ClientHeartHandler;
import dev.sunwenqi.tcpreverserelay.serverside.listen.recv.CommonReplyHandler;
import dev.sunwenqi.tcpreverserelay.serverside.listen.serversocket.ICreateServerSocket;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * <p>
 * 简单的交互、隧道；监听服务配置
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:53:17
 */
@Slf4j
@Data
@NoArgsConstructor
public class SimpleListenServerConfig implements IListenServerConfig {

    /**
     * 监听端口
     * 用于指定服务器监听的端口号
     */
    private Integer listenPort;

    /**
     * 无效时间毫秒数
     * 设定连接的无效时间，超过这个时间没有活动将被视为无效连接
     */
    private Long invalidMillis = 60000L;

    /**
     * 清除无效连接的时间间隔
     * 定义检查并清除无效连接的时间间隔
     */
    private Long clearInterval = 10L;

    /**
     * 字符集
     * 指定数据传输时使用的字符编码方式
     */
    private Charset charset = StandardCharsets.UTF_8;

    /**
     * 创建服务器套接字的接口
     * 用于抽象出创建服务器套接字的逻辑，以便于扩展和测试
     */
    private ICreateServerSocket createServerSocket;

    /**
     * 流缓存大小
     * 定义读取数据流时的缓存大小，以优化数据读取性能
     */
    private int streamCacheSize = 8196;

    public SimpleListenServerConfig(Integer listenPort) {
        this.listenPort = listenPort;
    }

    @Override
    public ServerSocket createServerSocket() throws Exception {
        if (this.createServerSocket == null) {
            ServerSocketChannel openServerSocketChannel = SelectorProvider.provider().openServerSocketChannel();
            // Permit prompt rebinding where the OS allows it. A port can still
            // remain unavailable briefly after active TCP traffic is closed.
            try {
                openServerSocketChannel.socket().setReuseAddress(true);
                openServerSocketChannel.bind(new InetSocketAddress(this.getListenPort()));
                return openServerSocketChannel.socket();
            } catch (Exception e) {
                openServerSocketChannel.close();
                throw e;
            }
        } else {
            return this.createServerSocket.createServerSocket(this.getListenPort());
        }
    }

    /**
     * 创建controlSocket使用channel
     *
     * @param socket
     * @return
     * @author Sunwenqi
     * @since 2025-04-15 13:19:49
     */
    protected SocketChannel<? extends InteractiveModel, ? super InteractiveModel> newControlSocketChannel(Socket socket) {
        InteractiveChannel interactiveChannel;
        try {
            interactiveChannel = new InteractiveChannel(socket);
        } catch (IOException e) {
            log.error("newControlSocketChannel exception", e);
            return null;
        }
        return interactiveChannel;
    }

    /**
     * 创建一个新的控制套接字
     *
     * @param socket 套接字连接
     * @param config 配置信息
     * @return 返回一个IControlSocket接口的实现对象
     */
    @Override
    public IControlSocket newControlSocket(Socket socket, JSONObject config) {
        // 创建一个新的控制套接字通道
        SocketChannel<? extends InteractiveModel, ? super InteractiveModel> controlSocketChannel = this
                .newControlSocketChannel(socket);
        // 实例化控制套接字
        ControlSocket controlSocket = new ControlSocket(controlSocketChannel);
        // 添加公共回复处理句柄
        controlSocket.addRecvHandler(CommonReplyHandler.INSTANCE);
        // 添加客户端心跳处理句柄
        controlSocket.addRecvHandler(ClientHeartHandler.INSTANCE);
        return controlSocket;
    }

    /**
     * 创建一个新的清理无效套接字部分的线程
     *
     * @param serverListenThread 服务器监听线程
     * @return 返回一个IClearInvalidSocketPartThread接口的实现对象
     */
    @Override
    public IClearInvalidSocketPartThread newClearInvalidSocketPartThread(ServerListenThread serverListenThread) {
        // 实例化清理无效套接字部分的线程
        ClearInvalidSocketPartThread clearInvalidSocketPartThread = new ClearInvalidSocketPartThread(
                serverListenThread);
        // 设置清理间隔时间（秒）
        clearInvalidSocketPartThread.setClearIntervalSeconds(this.getClearInterval());
        return clearInvalidSocketPartThread;
    }

    /**
     * 创建一个新的套接字部分
     *
     * @param serverListenThread 服务器监听线程
     * @return 返回一个AbsSocketPart抽象类的实现对象
     */
    @Override
    public AbsSocketPart newSocketPart(ServerListenThread serverListenThread) {
        // 实例化简单的套接字部分
        SimpleSocketPart socketPart = new SimpleSocketPart(serverListenThread);
        // 设置无效时间（毫秒）
        socketPart.setInvalidMillis(this.getInvalidMillis());
        // 设置流缓存大小
        socketPart.setStreamCacheSize(this.getStreamCacheSize());
        return socketPart;
    }


}
