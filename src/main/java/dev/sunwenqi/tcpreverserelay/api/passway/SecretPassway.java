package dev.sunwenqi.tcpreverserelay.api.passway;

import dev.sunwenqi.tcpreverserelay.api.IBelongControl;
import dev.sunwenqi.tcpreverserelay.api.secret.ISecret;
import dev.sunwenqi.tcpreverserelay.channel.LengthChannel;
import dev.sunwenqi.tcpreverserelay.executor.RelayExecutor;
import dev.sunwenqi.tcpreverserelay.nio.NioHallows;
import dev.sunwenqi.tcpreverserelay.utils.Tools;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;

/**
 * <p>
 * 加密型隧道，一侧加密，一侧原样输入、输出
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 15:55:29
 */
@Data
@Slf4j
public class SecretPassway implements Runnable {

    private boolean alive = false;
    private ISecret secret;
    private Mode mode;
    private IBelongControl belongControl;
    private int streamCacheSize = 8192;
    private Socket recvSocket;
    private Socket sendSocket;

    @Override
    public void run() {
        try {
            if (Mode.noToSecret.equals(this.mode)) {
                noToSecret();
            } else {
                secretToNo();
            }
        } catch (Exception e) {
            //
        }
        log.debug("one InputToOutputThread closed");
        // 传输完成后退出
        this.cancel();
    }

    /**
     * 从加密侧输入，解密后输出到无加密侧
     *
     * @throws Exception
     */
    private void secretToNo() throws Exception {
        LengthChannel recvChannel = new LengthChannel(this.recvSocket);

        OutputStream outputStream = this.sendSocket.getOutputStream();
        SocketChannel outputChannel = this.sendSocket.getChannel();

        ISecret secret = this.secret;

        byte[] read;
        byte[] decrypt;
        while (this.alive) {
            read = recvChannel.read();
            if (read == null) {
                break;
            }

            decrypt = secret.decrypt(read);

            if (Objects.isNull(outputChannel)) {
                outputStream.write(decrypt);
                outputStream.flush();
            } else {
                Tools.channelWrite(outputChannel, ByteBuffer.wrap(decrypt));
            }
        }
    }

    /**
     * 从无加密侧，经过加密后输出到加密侧
     *
     * @throws Exception
     */
    private void noToSecret() throws Exception {
        InputStream inputStream = this.recvSocket.getInputStream();

        LengthChannel sendChannel = new LengthChannel(this.sendSocket);

        // 字段赋值局部变量，入栈
        ISecret secret = this.secret;

        int len;
        byte[] arrayTemp = new byte[this.streamCacheSize];
        byte[] encrypt;
        while (this.alive && (len = inputStream.read(arrayTemp)) > 0) {
            encrypt = secret.encrypt(arrayTemp, 0, len);
            sendChannel.writeAndFlush(encrypt);
        }
    }

    /**
     * 判断是否有效
     *
     * @return
     */
    public boolean isValid() {
        return this.alive;
    }

    /**
     * 退出
     *
     * @author Sunwenqi
     * @since 2025-01-08 15:57:48
     */
    public void cancel() {
        if (!this.alive) {
            return;
        }
        this.alive = false;

        NioHallows.release(this.recvSocket.getChannel());

        try {
            Socket sendSocket;
            if ((sendSocket = this.sendSocket) != null) {
                // TCP 挥手步骤，对方调用 shutdownOutput 后等价完成 socket.close
                sendSocket.shutdownOutput();
            }
        } catch (IOException e) {
            // do nothing
        }

        IBelongControl belong;
        if ((belong = this.belongControl) != null) {
            this.belongControl = null;
            belong.noticeStop();
        }
    }

    /**
     * 启动
     */
    public void start() {
        if (this.alive) {
            return;
        }
        this.alive = true;
        RelayExecutor.executePassway(this);
    }

    /**
     * 通道模式
     */
    public enum Mode {
        /**
         * 从无加密接受到加密输出
         */
        noToSecret,
        /**
         * 从加密接受到无加密输出
         */
        secretToNo
    }

}
