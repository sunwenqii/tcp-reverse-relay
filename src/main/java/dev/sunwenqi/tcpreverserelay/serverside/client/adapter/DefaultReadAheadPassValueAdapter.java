package dev.sunwenqi.tcpreverserelay.serverside.client.adapter;

import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.serverside.client.config.IClientServiceConfig;
import dev.sunwenqi.tcpreverserelay.serverside.client.handler.DefaultInteractiveProcessHandler;

/**
 * <p>
 * 默认的预读后处理适配器
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-04-26 17:06:25
 */
public class DefaultReadAheadPassValueAdapter extends ReadAheadPassValueAdapter<InteractiveModel, InteractiveModel> {

    public DefaultReadAheadPassValueAdapter(IClientServiceConfig<InteractiveModel, InteractiveModel> config) {
        super(config);
        this.addLast(DefaultInteractiveProcessHandler.INSTANCE);
    }

}
