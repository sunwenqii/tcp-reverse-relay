package dev.sunwenqi.tcpreverserelay.channel;

import dev.sunwenqi.tcpreverserelay.utils.Tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>
 * 长度限定读写通道
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-03-08 12:42:38
 */
public class LengthChannel extends SocketChannel<byte[], byte[]> {

    // 握手报文和加密数据块共用此通道；单帧上限防止公网输入直接触发大内存分配。
    public static final int MAX_FRAME_BYTES = 1024 * 1024;

    private final ReentrantLock readLock = new ReentrantLock(true);
    private final ReentrantLock writerLock = new ReentrantLock(true);
    private final byte[] lenBytes = new byte[4];
    private Socket socket;
    private java.nio.channels.SocketChannel socketChannel;
    private InputStream inputStream;
    private OutputStream outputStream;

    public LengthChannel() {
    }

    public LengthChannel(Socket socket) throws IOException {
        this.setSocket(socket);
    }

    @Override
    public byte[] read() throws Exception {
        ReentrantLock readLock = this.readLock;
        byte[] lenBytes = this.lenBytes;

        readLock.lock();
        try {
            int offset = 0;

            InputStream is = getInputSteam();

            int len;
            while (offset < lenBytes.length) {
                len = is.read(lenBytes, offset, lenBytes.length - offset);
                if (len < 0) {
                    // 如果-1，提前关闭了，又没有获得足够的数据，那么就抛出异常
                    throw new IOException("Insufficient byte length[" + lenBytes.length + "] when io closed");
                }
                offset += len;
            }

            int length = Tools.bytes2int(lenBytes);
            if (length < 0 || length > MAX_FRAME_BYTES) {
                throw new IOException("Invalid frame length: " + length);
            }

            offset = 0;
            byte[] b = new byte[length];
            while (offset < length) {
                len = is.read(b, offset, length - offset);
                if (len < 0) {
                    // 如果-1，提前关闭了，又没有获得足够的数据，那么就抛出异常
                    throw new IOException("Insufficient byte length[" + length + "] when io closed");
                }
                offset += len;
            }

            return b;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void write(byte[] value) throws Exception {
        Objects.requireNonNull(value, "value");
        if (value.length > MAX_FRAME_BYTES) {
            throw new IOException("Frame too large: " + value.length);
        }
        ReentrantLock writerLock = this.writerLock;

        writerLock.lock();
        try {
            java.nio.channels.SocketChannel socketChannel;
            if (Objects.nonNull(socketChannel = this.socketChannel)) {
                Tools.channelWrite(socketChannel, ByteBuffer.wrap(Tools.intToBytes(value.length)));
                Tools.channelWrite(socketChannel, ByteBuffer.wrap(value));
            } else {
                OutputStream os = getOutputStream();
                os.write(Tools.intToBytes(value.length));
                os.write(value);
            }
        } finally {
            writerLock.unlock();
        }
    }

    @Override
    public void flush() throws Exception {
        ReentrantLock writerLock = this.writerLock;

        writerLock.lock();
        try {
            getOutputStream().flush();
        } finally {
            writerLock.unlock();
        }
    }

    @Override
    public void writeAndFlush(byte[] value) throws Exception {
        ReentrantLock writerLock = this.writerLock;

        writerLock.lock();
        try {
            this.write(value);
            this.flush();
        } finally {
            writerLock.unlock();
        }
    }

    @Override
    public Socket getSocket() {
        return this.socket;
    }

    @Override
    public void setSocket(Socket socket) throws IOException {
        if (Objects.nonNull(this.socket)) {
            throw new UnsupportedOperationException("socket cannot be set repeatedly");
        }

        this.socket = socket;

        this.socketChannel = socket.getChannel();

        this.inputStream = socket.getInputStream();
        this.outputStream = socket.getOutputStream();
    }

    @Override
    public void closeSocket() throws IOException {
        this.socket.close();
    }

    /**
     * 惰性获取输入流
     *
     * @return
     * @throws IOException
     * @author Sunwenqi
     * @since 2025-01-08 16:15:32
     */
    private InputStream getInputSteam() throws IOException {
        InputStream inputStream;
        if ((inputStream = this.inputStream) == null) {
            inputStream = this.inputStream = this.socket.getInputStream();
        }
        return inputStream;
    }

    /**
     * 惰性获取输出流
     *
     * @return
     * @throws IOException
     * @author Sunwenqi
     * @since 2025-01-08 16:15:48
     */
    private OutputStream getOutputStream() throws IOException {
        OutputStream outputStream;
        if ((outputStream = this.outputStream) == null) {
            outputStream = this.outputStream = this.getSocket().getOutputStream();
        }
        return outputStream;
    }

}
