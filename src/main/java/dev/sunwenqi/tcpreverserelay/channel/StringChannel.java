package dev.sunwenqi.tcpreverserelay.channel;

import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * <p>
 * 字符型通道
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:21:31
 */
public class StringChannel extends SocketChannel<String, String> {

    private final LengthChannel channel;

    @Setter
    @Getter
    private Charset charset = StandardCharsets.UTF_8;

    public StringChannel() {
        this.channel = new LengthChannel();
    }

    public StringChannel(Socket socket) throws IOException {
        this.channel = new LengthChannel(socket);
    }

    @Override
    public String read() throws Exception {
        byte[] read = this.channel.read();
        return new String(read, this.charset);
    }

    /**
     * 统一数据转换方法
     *
     * @param value
     * @return
     */
    private byte[] valueConvert(String value) {
        return value.getBytes(this.charset);
    }

    @Override
    public void write(String value) throws Exception {
        this.channel.write(this.valueConvert(value));
    }

    @Override
    public void flush() throws Exception {
        this.channel.flush();
    }

    @Override
    public void writeAndFlush(String value) throws Exception {
        this.channel.writeAndFlush(this.valueConvert(value));
    }


    @Override
    public Socket getSocket() {
        return this.channel.getSocket();
    }

    @Override
    public void setSocket(Socket socket) throws IOException {
        this.channel.setSocket(socket);
    }

    @Override
    public void closeSocket() throws IOException {
        this.channel.closeSocket();
    }

}
