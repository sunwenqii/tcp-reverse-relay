package dev.sunwenqi.tcpreverserelay.serverside.listen.config;

import dev.sunwenqi.tcpreverserelay.api.secret.AESSecret;
import dev.sunwenqi.tcpreverserelay.api.socketpart.AbsSocketPart;
import dev.sunwenqi.tcpreverserelay.api.socketpart.SecretSocketPart;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ServerListenThread;
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
 * @since 2025-01-08 15:05:55
 */
@Setter
@Getter
public class AllSecretSimpleListenServerConfig extends SecretSimpleListenServerConfig {

    private Key passwayKey;

    public AllSecretSimpleListenServerConfig(Integer listenPort) {
        super(listenPort);
    }

    @Override
    public AbsSocketPart newSocketPart(ServerListenThread serverListenThread) {
        AESSecret secret = new AESSecret();
        secret.setAesKey(this.passwayKey);
        SecretSocketPart secretSocketPart = new SecretSocketPart(serverListenThread);
        secretSocketPart.setSecret(secret);
        return secretSocketPart;
    }

    /**
     * BASE64格式设置隧道加密密钥
     *
     * @param key
     */
    public void setBasePasswayKey(String key) {
        this.passwayKey = AESUtil.createKeyByBase64(key);
    }

}
