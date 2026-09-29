package dev.sunwenqi.tcpreverserelay.channel;

import com.alibaba.fastjson.JSONObject;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * <p>
 * InteractiveModel 模式读写
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:11:50
 */
public class InteractiveChannel extends SocketChannel<InteractiveModel, InteractiveModel> {

    /**
     * 实际通道
     */
    private final JsonChannel channel;

    /**
     * 默认构造函数
     */
    public InteractiveChannel() {
        this.channel = new JsonChannel();
    }

    /**
     * 带Socket参数的构造函数
     *
     * @param socket Socket对象
     * @throws IOException 当创建JsonChannel时出现IO错误
     */
    public InteractiveChannel(Socket socket) throws IOException {
        this.channel = new JsonChannel(socket);
    }

    /**
     * 读取数据并转换为InteractiveModel对象
     *
     * @return InteractiveModel对象
     * @throws Exception 当读取或转换过程中发生错误
     */
    @Override
    public InteractiveModel read() throws Exception {
        JSONObject read = this.channel.read();
        return read.toJavaObject(InteractiveModel.class);
    }

    /**
     * 将InteractiveModel对象写入通道
     *
     * @param value 要写入的InteractiveModel对象
     * @throws Exception 当写入过程中发生错误
     */
    @Override
    public void write(InteractiveModel value) throws Exception {
        this.channel.write(value);
    }

    /**
     * 刷新通道
     *
     * @throws Exception 当刷新过程中发生错误
     */
    @Override
    public void flush() throws Exception {
        this.channel.flush();
    }

    /**
     * 写入并刷新通道
     *
     * @param value 要写入的InteractiveModel对象
     * @throws Exception 当写入或刷新过程中发生错误
     */
    @Override
    public void writeAndFlush(InteractiveModel value) throws Exception {
        this.channel.writeAndFlush(value);
    }

    /**
     * 获取charset
     *
     * @return 当前使用的Charset对象
     */
    public Charset getCharset() {
        return this.channel.getCharset();
    }

    /**
     * 设置charset
     *
     * @param charset 要设置的Charset对象
     */
    @Override
    public void setCharset(Charset charset) {
        this.channel.setCharset(charset);
    }

    /**
     * 获取Socket对象
     *
     * @return 当前使用的Socket对象
     */
    @Override
    public Socket getSocket() {
        return this.channel.getSocket();
    }

    /**
     * 设置Socket对象
     *
     * @param socket 要设置的Socket对象
     * @throws IOException 当设置Socket时出现IO错误
     */
    @Override
    public void setSocket(Socket socket) throws IOException {
        this.channel.setSocket(socket);
    }

    /**
     * 关闭Socket连接
     *
     * @throws IOException 当关闭Socket时出现IO错误
     */
    @Override
    public void closeSocket() throws IOException {
        this.channel.closeSocket();
    }

}
