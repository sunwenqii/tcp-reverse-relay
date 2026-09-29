package dev.sunwenqi.tcpreverserelay.clientside.handler;

import dev.sunwenqi.tcpreverserelay.clientside.adapter.IClientAdapter;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * <p>
 * 统一回复 处理器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-04-15 13:02:09
 */
@Setter
@Getter
@Slf4j
public class CommonReplyHandler implements IClientHandler<InteractiveModel, InteractiveModel> {

    public static final CommonReplyHandler INSTANCE = new CommonReplyHandler();

    private IClientHandler<InteractiveModel, InteractiveModel> handler;

    /**
     * 处理交互模型的方法
     *
     * @param model         交互模型，包含交互类型等信息
     * @param clientAdapter 客户端适配器，用于与客户端进行交互
     * @return 如果处理成功则返回true，否则返回false
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public boolean proc(InteractiveModel model,
                        IClientAdapter<? extends InteractiveModel, ? super InteractiveModel> clientAdapter) throws Exception {
        // 根据模型中的交互类型名称获取对应的枚举类型
        InteractiveTypeEnum interactiveTypeEnum = InteractiveTypeEnum.getEnumByName(model.getInteractiveType());
        // 检查交互类型是否为普通回复类型，如果不是则直接返回false
        if (!InteractiveTypeEnum.COMMON_REPLY.equals(interactiveTypeEnum)) {
            return false;
        }

        // 尝试获取处理器实例
        IClientHandler<InteractiveModel, InteractiveModel> handler;
        // 如果当前处理器为空，则记录日志并返回true
        if (Objects.isNull(handler = this.handler)) {
            log.debug("common reply: {}", model.getInteractiveType());
            return true;
        }

        // 使用当前处理器处理交互模型
        return handler.proc(model, clientAdapter);
    }

}
