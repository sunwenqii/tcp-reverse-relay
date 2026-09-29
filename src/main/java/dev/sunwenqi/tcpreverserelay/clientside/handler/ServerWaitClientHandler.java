package dev.sunwenqi.tcpreverserelay.clientside.handler;

import dev.sunwenqi.tcpreverserelay.clientside.adapter.IClientAdapter;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import dev.sunwenqi.tcpreverserelay.model.interactive.ServerWaitModel;

/**
 * <p>
 * 心跳检测 处理器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-04-15 13:02:09
 */
public class ServerWaitClientHandler implements IClientHandler<InteractiveModel, InteractiveModel> {

    // 单例模式实现，用于处理服务器等待客户端连接的交互模式
    public static final ServerWaitClientHandler INSTANCE = new ServerWaitClientHandler();

    /**
     * 处理交互模型的实现方法
     * 本方法专注于处理服务器等待客户端连接的交互类型
     * 对于其他类型的交互模型，本方法将返回false表示不处理
     *
     * @param model         交互模型，包含交互类型和数据
     * @param clientAdapter 客户端适配器，用于客户端连接操作
     * @return 如果成功处理服务器等待客户端连接的交互类型，则返回true；否则返回false
     * @throws Exception 如果处理过程中发生异常，则抛出此异常
     */
    @Override
    public boolean proc(InteractiveModel model,
                        IClientAdapter<? extends InteractiveModel, ? super InteractiveModel> clientAdapter) throws Exception {
        // 根据交互模型的类型获取对应的枚举类型
        InteractiveTypeEnum interactiveTypeEnum = InteractiveTypeEnum.getEnumByName(model.getInteractiveType());
        // 检查交互类型是否为服务器等待客户端连接类型，如果不是，则返回false
        if (!InteractiveTypeEnum.SERVER_WAIT_CLIENT.equals(interactiveTypeEnum)) {
            return false;
        }

        // 将模型的数据转换为ServerWaitModel类型，以便进行后续操作
        ServerWaitModel serverWaitModel = model.getData().toJavaObject(ServerWaitModel.class);
        // 使用客户端适配器发起客户端连接操作
        clientAdapter.clientConnect(serverWaitModel);

        return true;
    }

}
