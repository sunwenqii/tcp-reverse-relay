package dev.sunwenqi.tcpreverserelay.serverside.listen.control;

import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import dev.sunwenqi.tcpreverserelay.model.enumeration.RelayResultEnum;
import dev.sunwenqi.tcpreverserelay.model.interactive.ServerWaitModel;
import dev.sunwenqi.tcpreverserelay.serverside.listen.IServerListen;
import dev.sunwenqi.tcpreverserelay.serverside.listen.recv.IRecvHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import dev.sunwenqi.tcpreverserelay.model.interactive.ClientControlModel;
import dev.sunwenqi.tcpreverserelay.model.interactive.TargetProbeResult;

/**
 * <p>
 * 控制socket实例
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-07-17 11:03:56
 */
@Slf4j
public class ControlSocket implements IControlSocket, Runnable {

    protected final SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel;
    protected List<IRecvHandler<? super InteractiveModel, ? extends InteractiveModel>> recvHandlerList = new LinkedList<>();
    protected IServerListen serverListen;
    private volatile Thread myThread = null;
    private volatile boolean started = false;
    private volatile boolean cancelled = false;
    private volatile long lastActivityAt = System.currentTimeMillis();
    private final ConcurrentHashMap<String, CompletableFuture<TargetProbeResult>> probes = new ConcurrentHashMap<>();

    @Override public long getLastActivityAt() { return lastActivityAt; }

    @Override public TargetProbeResult probeTarget() throws Exception {
        if (cancelled || probes.size() >= 4) throw new IOException("客户端离线或诊断任务繁忙");
        InteractiveModel request = InteractiveModel.of(InteractiveTypeEnum.TARGET_PROBE, null);
        CompletableFuture<TargetProbeResult> result = new CompletableFuture<>();
        probes.put(request.getInteractiveSeq(), result);
        try {
            socketChannel.writeAndFlush(request);
            return result.get(4, TimeUnit.SECONDS);
        } finally { probes.remove(request.getInteractiveSeq()); }
    }

    public ControlSocket(SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel) {
        this.socketChannel = socketChannel;
    }

    @Override
    public boolean isValid() {
        SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel = this.socketChannel;

        Socket socket = (socketChannel == null) ? null : socketChannel.getSocket();
        boolean closeFlag = socket == null || !socket.isConnected() || socket.isClosed() || socket.isInputShutdown() ||
                socket.isOutputShutdown();
        if (closeFlag) {
            return false;
        }

        try {
            // 心跳测试
            InteractiveModel interactiveModel = InteractiveModel.of(InteractiveTypeEnum.HEART_TEST, null);
            socketChannel.writeAndFlush(interactiveModel);
        } catch (Exception e) {
            return false;
        }

        return true;
    }

    @Override
    public void close() {
        if (this.cancelled) {
            return;
        }
        this.cancelled = true;
        probes.values().forEach(future -> future.completeExceptionally(new IOException("客户端控制连接已断开")));
        probes.clear();

        Thread myThread;
        if ((myThread = this.myThread) != null) {
            this.myThread = null;
            myThread.interrupt();
        }

        if (this.socketChannel != null) {
            try {
                this.socketChannel.close();
            } catch (IOException e) {
                // do nothing
            }
        }

        IServerListen serverListenThread = this.serverListen;
        if (Objects.nonNull(serverListenThread)) {
            this.serverListen = null;
            serverListenThread.controlCloseNotice(this);
        }

    }

    @Override
    public boolean sendClientWait(String socketPartKey) {
        InteractiveModel model = InteractiveModel.of(InteractiveTypeEnum.SERVER_WAIT_CLIENT,
                new ServerWaitModel(socketPartKey));

        try {
            this.socketChannel.writeAndFlush(model);
        } catch (Throwable e) {
            return false;
        }

        return true;
    }

    @Override
    public void startRecv() {
        if (this.started) {
            return;
        }
        this.started = true;

        Thread myThread = this.myThread;
        if (myThread == null || !myThread.isAlive()) {
            myThread = this.myThread = new Thread(this);
            myThread.setName("control-recv-" + this.formatServerListenInfo());
            myThread.start();
        }
    }

    @Override
    public void run() {
        SocketChannel<? extends InteractiveModel, ? super InteractiveModel> socketChannel = this.socketChannel;
        while (this.started && !this.cancelled) {
            try {
                InteractiveModel interactiveModel = socketChannel.read();
                lastActivityAt = System.currentTimeMillis();
                if (InteractiveTypeEnum.CLIENT_INFO.name().equals(interactiveModel.getInteractiveType())) {
                    serverListen.updateClientInfo(this, interactiveModel.getData().toJavaObject(ClientControlModel.class));
                    continue;
                }
                CompletableFuture<TargetProbeResult> probe = interactiveModel.getInteractiveSeq() == null
                        ? null : probes.get(interactiveModel.getInteractiveSeq());
                if (probe != null) {
                    if (InteractiveTypeEnum.TARGET_PROBE_RESULT.name().equals(interactiveModel.getInteractiveType()))
                        probe.complete(interactiveModel.getData().toJavaObject(TargetProbeResult.class));
                    else probe.completeExceptionally(new IOException("客户端版本不支持远程诊断"));
                    continue;
                }

                log.debug("监听线程 [{}] 接收到控制端口发来的消息：[ {} ]", this.formatServerListenInfo(),
                        interactiveModel);

                boolean proc = false;
                for (IRecvHandler<? super InteractiveModel, ? extends InteractiveModel> handler : this.recvHandlerList) {
                    proc = handler.proc(interactiveModel, this.socketChannel);
                    if (proc) {
                        break;
                    }
                }

                if (!proc) {
                    log.warn("端口 {} 收到未支持的控制消息类型: {}", this.formatServerListenInfo(), interactiveModel.getInteractiveType());

                    InteractiveModel result = InteractiveModel.of(interactiveModel.getInteractiveSeq(),
                            InteractiveTypeEnum.COMMON_REPLY,
                            RelayResultEnum.UNKNOWN_INTERACTIVE_TYPE.toResultModel());
                    socketChannel.writeAndFlush(result);
                }

            } catch (Exception e) {
                if (this.cancelled || !this.started) break;
                if (e instanceof IOException) log.warn("端口 {} 控制连接已断开: {}", this.formatServerListenInfo(), e.getMessage());
                else log.error("端口 " + this.formatServerListenInfo() + " 控制消息处理失败", e);
                if (e instanceof IOException || !this.isValid()) {
                    this.close();
                }
            }
        }
    }

    private String formatServerListenInfo() {
        if (Objects.isNull(this.serverListen)) {
            return null;
        }
        return this.serverListen.formatInfo();
    }

    @Override
    public void setServerListen(IServerListen serverListen) {
        this.serverListen = serverListen;
    }

    /**
     * 添加处理器
     *
     * @param handler
     * @return
     * @author Sunwenqi
     * @since 2025-04-15 13:13:24
     */
    public ControlSocket addRecvHandler(IRecvHandler<? super InteractiveModel, ? extends InteractiveModel> handler) {
        this.recvHandlerList.add(handler);
        return this;
    }

}
