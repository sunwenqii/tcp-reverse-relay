package dev.sunwenqi.tcpreverserelay.forward;

import java.io.IOException;
import java.util.List;

import dev.sunwenqi.tcpreverserelay.config.ServerConnection;
import dev.sunwenqi.tcpreverserelay.health.HealthSettings;

/**
 * Common operations exposed to the local management interface.
 */
public interface ForwardManager {
    List<ForwardStatus> list();

    void upsert(Integer oldPort, int port, Integer target) throws IOException;

    void delete(int port) throws IOException;

    default void upsert(Integer oldPort, int port, String targetHost, Integer target) throws IOException {
        upsert(oldPort, port, target);
    }
    default BatchResult batch(String action, String entries, boolean preview) throws IOException {
        throw new IllegalArgumentException("当前运行模块不支持批量操作");
    }
    default DiagnosticReport diagnose(int port) {
        throw new IllegalArgumentException("当前运行模块不支持诊断");
    }
    default void assignOwner(int port, String clientId) throws IOException { throw new IllegalArgumentException("仅服务端支持端口归属管理"); }
    default HealthSettings healthSettings() { throw new IllegalArgumentException("仅客户端可设置目标检查"); }
    default void configureHealth(HealthSettings settings) throws IOException { throw new IllegalArgumentException("仅客户端可设置目标检查"); }
    default ConnectionStatus connectionStatus() { return null; }
    default boolean configureConnection(ServerConnection connection) throws IOException { throw new IllegalArgumentException("仅客户端支持服务器连接设置"); }
    default List<ClientInfo> clients() { return List.of(); }
}
