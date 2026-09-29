package dev.sunwenqi.tcpreverserelay.clientside.config;

import dev.sunwenqi.tcpreverserelay.api.secret.AESSecret;
import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.api.socketpart.SecretSocketPart;
import dev.sunwenqi.tcpreverserelay.clientside.ClientControlThread;
import dev.sunwenqi.tcpreverserelay.utils.AESUtil;
import lombok.Getter;
import lombok.Setter;

import java.security.Key;

/**
 * <p>
 * 交互及隧道都加密
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 15:01:44
 */
public class AllSecretInteractiveClientConfig extends SecretInteractiveClientConfig {

    @Setter
    @Getter
    private Key passwayKey;

    @Override
    public AbsSocketPart newSocketPart(ClientControlThread clientControlThread) {
        AESSecret secret = new AESSecret();
        secret.setAesKey(this.passwayKey);
        SecretSocketPart secretSocketPart = new SecretSocketPart(clientControlThread);
        secretSocketPart.setSecret(secret);
        return secretSocketPart;
    }

    /**
     * base64格式设置密钥
     *
     * @param key
     */
    public void setBasePasswayKey(String key) {
        this.passwayKey = AESUtil.createKeyByBase64(key);
    }

}
