package dev.sunwenqi.tcpreverserelay.clientside.handler;

import dev.sunwenqi.tcpreverserelay.clientside.adapter.IClientAdapter;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import dev.sunwenqi.tcpreverserelay.model.enumeration.RelayResultEnum;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * 心跳检测 处理器
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-04-15 13:02:09
 */
@Slf4j
public class ServerHeartHandler implements IClientHandler<InteractiveModel, InteractiveModel> {

    public static final ServerHeartHandler INSTANCE = new ServerHeartHandler();

    @Override
    public boolean proc(InteractiveModel model,
                        IClientAdapter<? extends InteractiveModel, ? super InteractiveModel> clientAdapter) throws Exception {

        // 根据模型中的交互类型获取对应的枚举类型
        InteractiveTypeEnum interactiveTypeEnum = InteractiveTypeEnum.getEnumByName(model.getInteractiveType());

        // 判断交互类型是否为心跳测试
        if (InteractiveTypeEnum.HEART_TEST.equals(interactiveTypeEnum)) {
            // 当交互类型为心跳测试时，创建一个回复模型，包含成功的测试结果
            InteractiveModel sendModel = InteractiveModel.of(model.getInteractiveSeq(), InteractiveTypeEnum.HEART_TEST_REPLY,
                    RelayResultEnum.SUCCESS.toResultModel());
            // 将回复模型写入并冲刷到客户端的SocketChannel中
            clientAdapter.getSocketChannel().writeAndFlush(sendModel);

            return true;
        } else if (InteractiveTypeEnum.HEART_TEST_REPLY.equals(interactiveTypeEnum)) {
            // 当交互类型为心跳测试回复时，进行忽略处理并记录日志
            log.debug("HEART_TEST_REPLY ignore");
            return true;
        }

        return false;
    }

}
