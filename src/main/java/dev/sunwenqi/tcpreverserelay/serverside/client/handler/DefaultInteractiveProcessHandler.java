package dev.sunwenqi.tcpreverserelay.serverside.client.handler;

import dev.sunwenqi.tcpreverserelay.serverside.client.process.ClientConnectProcess;
import dev.sunwenqi.tcpreverserelay.serverside.client.process.ClientControlProcess;

/**
 * <p>
 * 默认的接收处理handler
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-04-26 17:22:31
 */
public class DefaultInteractiveProcessHandler extends InteractiveProcessHandler {

    public static final DefaultInteractiveProcessHandler INSTANCE = new DefaultInteractiveProcessHandler();

    public DefaultInteractiveProcessHandler() {
        this.addLast(ClientControlProcess.INSTANCE);
        this.addLast(ClientConnectProcess.INSTANCE);
    }

}
