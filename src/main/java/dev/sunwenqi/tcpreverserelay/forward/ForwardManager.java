package dev.sunwenqi.tcpreverserelay.forward;

import java.io.IOException;
import java.util.List;

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
    default List<ClientInfo> clients() { return List.of(); }
}
