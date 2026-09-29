package dev.sunwenqi.tcpreverserelay.serverside.listen.serversocket;

import java.net.ServerSocket;

/**
 * <p>
 * 创建服务端口接口
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-09 13:28:12
 */
public interface ICreateServerSocket {

    /**
     * 创建监听服务
     *
     * @param listenPort
     * @return
     */
    ServerSocket createServerSocket(int listenPort) throws Exception;

}
