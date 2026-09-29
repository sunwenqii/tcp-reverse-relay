package dev.sunwenqi.tcpreverserelay.serverside.listen.recv;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import dev.sunwenqi.tcpreverserelay.model.enumeration.RelayResultEnum;

/**
 * <p>
 * 心跳检测
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-04-15 13:02:09
 */
public class ClientHeartHandler implements IRecvHandler<InteractiveModel, InteractiveModel> {

    // 客户端心跳处理器的单例实例，用于处理心跳交互
    public static final ClientHeartHandler INSTANCE = new ClientHeartHandler();

    /**
     * 处理交互模型的方法，专注于心跳测试的场景
     *
     * @param model   交互模型，包含了交互类型、序列号等信息
     * @param channel 交互模型的通道，用于数据的读写
     * @return 如果成功处理心跳测试，则返回true；否则返回false
     * @throws Exception 如果在处理过程中遇到异常，则抛出此异常
     */
    @Override
    public boolean proc(InteractiveModel model,
                        SocketChannel<? extends InteractiveModel, ? super InteractiveModel> channel) throws Exception {
        // 获取交互类型的枚举实例
        InteractiveTypeEnum interactiveTypeEnum = InteractiveTypeEnum.getEnumByName(model.getInteractiveType());
        // 检查交互类型是否为心跳测试，只有心跳测试类型才进行处理
        if (!InteractiveTypeEnum.HEART_TEST.equals(interactiveTypeEnum)) {
            return false;
        }
        // 构建回复的交互模型，设置交互类型为心跳测试回复，并设置成功结果
        InteractiveModel sendModel = InteractiveModel.of(model.getInteractiveSeq(), InteractiveTypeEnum.HEART_TEST_REPLY,
                RelayResultEnum.SUCCESS.toResultModel());

        // 将回复的交互模型写入通道并刷新，以发送回复
        channel.writeAndFlush(sendModel);

        return true;
    }

}
