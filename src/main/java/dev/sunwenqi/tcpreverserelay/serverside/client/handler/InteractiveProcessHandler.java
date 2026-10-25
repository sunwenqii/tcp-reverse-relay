package dev.sunwenqi.tcpreverserelay.serverside.client.handler;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.common.Optional;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import dev.sunwenqi.tcpreverserelay.model.enumeration.RelayResultEnum;
import dev.sunwenqi.tcpreverserelay.serverside.client.adapter.PassValueNextEnum;
import dev.sunwenqi.tcpreverserelay.serverside.client.process.IProcess;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.List;

/**
 * <p>
 * 常规接收处理handler
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-01-06 10:20:11
 */
@Getter
@Slf4j
public class InteractiveProcessHandler implements IPassValueHandler<InteractiveModel, InteractiveModel> {

    /**
     * 处理链表
     * -- GETTER --
     * 获取处理链表，可以代理维护
     */
    private List<IProcess> processList = new LinkedList<>();

    @Override
    public PassValueNextEnum proc(SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel,
                                  Optional<? extends InteractiveModel> optional) {
        InteractiveModel value = optional.getValue();
        log.debug("接收到新消息：[ {} ]", value);

        for (IProcess process : this.processList) {
            boolean wouldProc = process.wouldProc(value);
            if (wouldProc) {
                boolean processMethod;
                try {
                    processMethod = process.processMethod(socketChannel, value);
                } catch (Exception e) {
                    log.error("处理任务时发生异常", e);
                    return PassValueNextEnum.STOP_CLOSE;
                }
                if (processMethod) {
                    return PassValueNextEnum.STOP_KEEP;
                } else {
                    return PassValueNextEnum.STOP_CLOSE;
                }
            }
        }

        try {
            socketChannel.writeAndFlush(InteractiveModel.of(value.getInteractiveSeq(), InteractiveTypeEnum.COMMON_REPLY,
                    RelayResultEnum.UNKNOWN_INTERACTIVE_TYPE.toResultModel()));
        } catch (Exception e) {
            log.error("发送消息时异常", e);
        }

        return PassValueNextEnum.STOP_CLOSE;
    }

    /**
     * 将处理方法加入后面
     */
    public InteractiveProcessHandler addLast(IProcess process) {
        this.processList.add(process);
        return this;
    }

    /**
     * 设置处理链表
     */
    public InteractiveProcessHandler setProcessList(List<IProcess> processList) {
        this.processList = processList;
        return this;
    }

}
