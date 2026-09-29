package dev.sunwenqi.tcpreverserelay.serverside.listen.config;

import com.alibaba.fastjson.JSONObject;
import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ServerListenThread;
import dev.sunwenqi.tcpreverserelay.serverside.listen.clear.IClearInvalidSocketPartThread;
import dev.sunwenqi.tcpreverserelay.serverside.listen.control.IControlSocket;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * <p>
 * 穿透监听服务配置
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:51:04
 */
public interface IListenServerConfig {

    /**
     * 获取监听的端口
     */
    Integer getListenPort();

    /**
     * 新建无效端口处理线程
     */
    IClearInvalidSocketPartThread newClearInvalidSocketPartThread(ServerListenThread serverListenThread);

    /**
     * 创建隧道伙伴
     */
    AbsSocketPart newSocketPart(ServerListenThread serverListenThread);

    /**
     * 获取字符集
     */
    Charset getCharset();

    /**
     * 新建控制器
     */
    IControlSocket newControlSocket(Socket socket, JSONObject config);

    /**
     * 创建监听端口
     */
    ServerSocket createServerSocket() throws Exception;
}
