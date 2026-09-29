package dev.sunwenqi.tcpreverserelay.serverside.listen.recv;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
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
public class CommonReplyHandler implements IRecvHandler<InteractiveModel, InteractiveModel> {

    public static final CommonReplyHandler INSTANCE = new CommonReplyHandler();

    private IRecvHandler<InteractiveModel, InteractiveModel> handler;

    /**
     * 处理交互模型的通用回复逻辑
     *
     * @param model   交互模型，包含交互类型等信息
     * @param channel 交互模型的通道，用于进一步处理或响应
     * @return boolean 表示处理是否成功
     * @throws Exception 如果处理过程中发生错误
     */
    @Override
    public boolean proc(InteractiveModel model,
                        SocketChannel<? extends InteractiveModel, ? super InteractiveModel> channel) throws Exception {
        // 根据模型中的交互类型名称获取交互类型枚举
        InteractiveTypeEnum interactiveTypeEnum = InteractiveTypeEnum.getEnumByName(model.getInteractiveType());
        // 检查交互类型是否为通用回复，如果不是，则返回false表示不处理
        if (!InteractiveTypeEnum.COMMON_REPLY.equals(interactiveTypeEnum)) {
            return false;
        }

        // 尝试获取处理交互模型的接收处理器
        IRecvHandler<InteractiveModel, InteractiveModel> handler;
        // 如果处理器为空，则记录通用回复的日志并返回true表示处理成功
        if (Objects.isNull(handler = this.handler)) {
            log.info("common reply: {}", model);
            return true;
        }

        // 使用处理器处理交互模型和通道，返回处理结果
        return handler.proc(model, channel);
    }

}
