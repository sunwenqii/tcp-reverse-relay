package dev.sunwenqi.tcpreverserelay.clientside.handler;

import dev.sunwenqi.tcpreverserelay.clientside.adapter.IClientAdapter;

/**
 * <p>
 * 接收处理器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-04-15 11:13:20
 */
public interface IClientHandler<R, W> {

    /**
     * 执行方法
     *
     * @param model
     * @param clientAdapter
     * @return
     * @throws Exception
     */
    boolean proc(R model, IClientAdapter<? extends R, ? super W> clientAdapter) throws Exception;

}
