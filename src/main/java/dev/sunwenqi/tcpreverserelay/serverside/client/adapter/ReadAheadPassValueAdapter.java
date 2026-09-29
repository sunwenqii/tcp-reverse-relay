package dev.sunwenqi.tcpreverserelay.serverside.client.adapter;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.common.Optional;
import dev.sunwenqi.tcpreverserelay.serverside.client.config.IClientServiceConfig;
import dev.sunwenqi.tcpreverserelay.serverside.client.handler.IPassValueHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;

/**
 * <p>
 * 预读后处理适配器
 * </p>
 *
 * @param <R>
 * @param <W>
 * @author Sunwenqi
 * @since 2025-01-06 08:56:22
 */
@Slf4j
public class ReadAheadPassValueAdapter<R, W> implements IClientServiceAdapter {

    /**
     * 客户端服务配置
     */
    private final IClientServiceConfig<R, W> config;
    /**
     * 处理器队列
     * -- GETTER --
     * 获取处理器，可以自行更改
     *
     * @return
     */
    @Getter
    private List<IPassValueHandler<? super R, ? extends W>> handlerList = new LinkedList<>();

    public ReadAheadPassValueAdapter(IClientServiceConfig<R, W> config) {
        this.config = config;
    }

    @Override
    public void procMethod(Socket listenSocket) throws Exception {
        // 建立交互通道
        SocketChannel<? extends R, ? super W> socketChannel;
        try {
            socketChannel = this.config.newSocketChannel(listenSocket);
        } catch (Exception e) {
            log.error("创建socket通道异常", e);
            throw e;
        }

        Optional<R> optional;
        try {
            R read = socketChannel.read();
            // 只限制首次握手；成功后控制连接和数据隧道必须保持长连接。
            listenSocket.setSoTimeout(0);
            optional = Optional.of(read);
        } catch (Exception e) {
            throw e;
        }

        boolean closeFlag = true;
        for (IPassValueHandler<? super R, ? extends W> handler : handlerList) {
            // 按照队列进行执行
            PassValueNextEnum proc = handler.proc(socketChannel, optional);

            // 只要有一个需要不关闭通道则就不得关闭
            if (!proc.isCloseFlag()) {
                closeFlag = false;
            }
            // 如果无需继续向下则退出
            if (!proc.isNextFlag()) {
                break;
            }
        }

        if (closeFlag) {
            try {
                socketChannel.closeSocket();
            } catch (IOException e) {
                log.error("关闭socket异常", e);
            }
        }

    }

    /**
     * 添加处理器到最后一个
     *
     * @param handler
     * @return
     * @author Sunwenqi
     * @since 2025-01-06 09:06:55
     */
    public ReadAheadPassValueAdapter<R, W> addLast(IPassValueHandler<? super R, ? extends W> handler) {
        this.handlerList.add(handler);
        return this;
    }

    /**
     * 设置handlerList
     *
     * @param handlerList
     * @return
     * @author Sunwenqi
     * @since 2025-01-06 09:07:53
     */
    public ReadAheadPassValueAdapter<R, W> setHandlerList(List<IPassValueHandler<? super R, ? extends W>> handlerList) {
        this.handlerList = handlerList;
        return this;
    }

}
