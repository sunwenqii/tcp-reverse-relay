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
}
