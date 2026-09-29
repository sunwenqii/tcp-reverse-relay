package dev.sunwenqi.tcpreverserelay.api.socketpart;

import dev.sunwenqi.tcpreverserelay.api.IBelongControl;
import dev.sunwenqi.tcpreverserelay.api.passway.SimplePassway;
import dev.sunwenqi.tcpreverserelay.utils.Assert;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

/**
 * <p>
 * socket匹配对
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-07-12 08:36:30
 */
@Slf4j
public class SimpleSocketPart extends AbsSocketPart implements IBelongControl {

    private final CountDownLatch cancelLatch = new CountDownLatch(2);
    protected SimplePassway outToInPassway;
    protected SimplePassway inToOutPassway;
    @Getter
    @Setter
    private int streamCacheSize = 8192;

    public SimpleSocketPart(IBelongControl belongThread) {
        super(belongThread);
    }

    /**
     * 停止，并告知上层处理掉
     */
    public void stop() {
        this.cancel();

        IBelongControl belong;
        if ((belong = this.belongThread) != null) {
            this.belongThread = null;
            belong.stopSocketPart(this.socketPartKey);
        }
    }

    @Override
    public void cancel() {
        if (this.canceled) {
            return;
        }
        this.canceled = true;
        this.isAlive = false;

        log.debug("socketPart {} will cancel", this.socketPartKey);

        SimplePassway outToInPassway;
        if ((outToInPassway = this.outToInPassway) != null) {
            this.outToInPassway = null;
            outToInPassway.cancel();
        }
        SimplePassway inToOutPassway;
        if ((inToOutPassway = this.inToOutPassway) != null) {
            this.inToOutPassway = null;
            inToOutPassway.cancel();
        }

        Socket recvSocket;
        if ((recvSocket = this.recvSocket) != null) {
            this.recvSocket = null;
            try {
                recvSocket.close();
            } catch (IOException e) {
                log.debug("socketPart [{}] 监听端口 关闭异常", socketPartKey);
            }
        }

        Socket sendSocket;
        if ((sendSocket = this.sendSocket) != null) {
            this.sendSocket = null;
            try {
                sendSocket.close();
            } catch (IOException e) {
                log.debug("socketPart [{}] 发送端口 关闭异常", socketPartKey);
            }
        }

        log.debug("socketPart {} is cancelled", this.socketPartKey);
    }

    @Override
    public boolean createPassWay() {
        Assert.state(!this.canceled, "不得重启已退出的socketPart");

        if (this.isAlive) {
            return true;
        }
        this.isAlive = true;

        try {
            SimplePassway outToInPassway = this.outToInPassway = new SimplePassway();
            outToInPassway.setBelongControl(this);
            outToInPassway.setRecvSocket(this.recvSocket);
            outToInPassway.setSendSocket(this.sendSocket);
            outToInPassway.setStreamCacheSize(getStreamCacheSize());

            SimplePassway inToOutPassway = this.inToOutPassway = new SimplePassway();
            inToOutPassway.setBelongControl(this);
            inToOutPassway.setRecvSocket(this.sendSocket);
            inToOutPassway.setSendSocket(this.recvSocket);
            inToOutPassway.setStreamCacheSize(getStreamCacheSize());

            outToInPassway.start();
            inToOutPassway.start();
        } catch (Exception e) {
            log.error("socketPart [" + this.socketPartKey + "] 隧道建立异常", e);
            this.stop();
            return false;
        }
        return true;
    }

    @Override
    public void noticeStop() {
        CountDownLatch cancellLatch = this.cancelLatch;
        cancellLatch.countDown();
        if (cancellLatch.getCount() <= 0) {
            this.stop();
        }
    }

}
