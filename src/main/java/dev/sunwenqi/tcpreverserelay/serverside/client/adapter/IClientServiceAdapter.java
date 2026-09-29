package dev.sunwenqi.tcpreverserelay.serverside.client.adapter;

import java.net.Socket;

/**
 * <p>
 * 客户端服务适配器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:40:35
 */
public interface IClientServiceAdapter {

    /**
     * 处理方法
     *
     * @param listenSocket
     * @throws Exception
     */
    void procMethod(Socket listenSocket) throws Exception;

}
