package dev.sunwenqi.tcpreverserelay.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.sunwenqi.tcpreverserelay.utils.AESUtil;
import dev.sunwenqi.tcpreverserelay.utils.HmacSignature;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * <p>
 * 基于InteractiveModel模型的加密交互模型
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:38:52
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class SecretInteractiveModel extends InteractiveModel {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 时间戳
     */
    private Long timestamp;
    private String nonce;
    /**
     * 签名
     */
    private String autograph;
    /**
     * InteractiveModel模型jsonString加密值
     */
    private String encrypt;
    /**
     * 字符编码
     */
    private String charset = StandardCharsets.UTF_8.name();

    public SecretInteractiveModel(InteractiveModel model) {
        super(model);
    }

    /**
     * 加密消息
     *
     * @param key
     * @throws Exception
     * @author Sunwenqi
     * @since 2025-01-08 16:39:46
     */
    public void encryptMsg(Key key) throws Exception {
        this.encrypt = AESUtil.encryptBase64(key, super.toJSONString().getBytes(this.charset));
    }

    /**
     * 解密消息
     *
     * @param key
     * @throws Exception
     * @author Sunwenqi
     * @since 2025-01-08 16:39:53
     */
    public void decryptMsg(Key key) throws Exception {
        // 使用AES工具类的decryptBase64方法解密密文
        // key为解密密钥，this.encrypt为待解密的密文
        byte[] decryptBase64 = AESUtil.decryptBase64(key, this.encrypt);

        // 将解密后的字节数组转换为字符串
        // this.charset为字符编码，确保转换的准确性
        String interactiveJsonString = new String(decryptBase64, this.charset);

        // 使用JSON工具类将JSON字符串解析为InteractiveModel对象
        InteractiveModel model = JSON.parseObject(interactiveJsonString, InteractiveModel.class);

        // 调用父类的fullValue方法对解析出的InteractiveModel对象进行处理
        // 该方法可能用于填充或处理model中的某些字段或值
        super.fullValue(model);
    }

    /**
     * 签名模型
     *
     * @param tokenKey
     * @author Sunwenqi
     * @since 2025-01-08 16:39:59
     */
    public void autographMsg(String tokenKey) {
        this.autograph = HmacSignature.sign(tokenKey, this.timestamp.toString(), this.nonce,
                this.charset, this.encrypt);
    }

    /**
     * 检查签名
     *
     * @param tokenKey
     * @return
     * @author Sunwenqi
     * @since 2025-01-08 16:40:09
     */
    public boolean checkAutograph(String tokenKey) {
        if (this.timestamp == null || this.nonce == null || this.charset == null || this.encrypt == null) {
            return false;
        }
        String signature = HmacSignature.sign(tokenKey, this.timestamp.toString(), this.nonce,
                this.charset, this.encrypt);
        return HmacSignature.verify(this.autograph, signature);
    }

    /**
     * 填充消息
     *
     * @param key
     * @param tokenKey
     * @throws Exception
     * @author Sunwenqi
     * @since 2025-01-08 16:40:17
     */
    public void fullMessage(Key key, String tokenKey) throws Exception {
        this.timestamp = System.currentTimeMillis();
        byte[] nonceBytes = new byte[16];
        RANDOM.nextBytes(nonceBytes);
        this.nonce = Base64.getEncoder().encodeToString(nonceBytes);
        this.encryptMsg(key);
        this.autographMsg(tokenKey);
    }

    @Override
    public String toJSONString() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("charset", this.charset);
        jsonObject.put("timestamp", this.timestamp);
        jsonObject.put("nonce", this.nonce);
        jsonObject.put("encrypt", this.encrypt);
        jsonObject.put("autograph", this.autograph);
        return jsonObject.toJSONString();
    }

}
