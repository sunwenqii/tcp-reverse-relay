package dev.sunwenqi.tcpreverserelay.clientside.config;

import java.security.Key;

import dev.sunwenqi.tcpreverserelay.channel.SecretInteractiveChannel;
import dev.sunwenqi.tcpreverserelay.channel.SocketChannel;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.utils.AESUtil;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * 交互加密的配置方案（AES加密）
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:32:18
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = false)
public class SecretInteractiveClientConfig extends InteractiveClientConfig {

    private String tokenKey;
    private Key aesKey;

    @Override
    public SocketChannel<? extends InteractiveModel, ? super InteractiveModel> newClientChannel() {
        SecretInteractiveChannel channel = new SecretInteractiveChannel();

        channel.setCharset(this.getCharset());
        channel.setTokenKey(this.tokenKey);
        channel.setAesKey(this.aesKey);

        connectService(channel);
        return channel;
    }

    /**
     * 设置交互密钥
     *
     * @param aesKey
     */
    public void setBaseAesKey(String aesKey) {
        this.aesKey = AESUtil.createKeyByBase64(aesKey);
    }

}
