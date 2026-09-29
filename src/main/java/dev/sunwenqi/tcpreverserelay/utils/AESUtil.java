package dev.sunwenqi.tcpreverserelay.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * <p>
 * AES加解密工具
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-01-08 16:56:05
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AESUtil {

    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 根据base64获取aes密钥
     *
     * @param baseKey
     * @return
     */
    public static Key createKeyByBase64(String baseKey) {
        byte[] keyBytes = Base64.getDecoder().decode(baseKey);
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 生成随机密钥
     *
     * @param length
     * @return
     */
    public static Key createKey(int length) {
        try {
            // 生成key
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            // 生成一个length位的随机源,根据传入的字节数组
            keyGenerator.init(length);
            // 产生原始对称密钥
            SecretKey secretKey = keyGenerator.generateKey();
            // 获得原始对称密钥的字节数组
            byte[] keyBytes = secretKey.getEncoded();
            // key转换,根据字节数组生成AES密钥
            return new SecretKeySpec(keyBytes, "AES");
        } catch (NoSuchAlgorithmException e) {
            log.error("create aes key exception", e);
            return null;
        }
    }

    /**
     * 使用 AES-GCM 加密并转换为 Base64；结果包含随机 nonce。
     *
     * @param key
     * @param content
     * @return
     * @throws Exception
     */
    public static String encryptBase64(Key key, byte[] content) throws Exception {
        byte[] encrypt = encrypt(key, content);
        return Base64.getEncoder().encodeToString(encrypt);
    }

    /**
     * 使用 AES-GCM 加密，结果前 12 字节为随机 nonce。
     *
     * @param key
     * @param content
     * @return
     * @throws Exception
     */
    public static byte[] encrypt(Key key, byte[] content) throws Exception {
        return encrypt(key, content, 0, content.length);
    }

    /**
     * 使用 AES-GCM 加密指定范围的数据。
     *
     * @param key
     * @param content
     * @param inputOffset
     * @param inputLen
     * @return
     * @throws Exception
     */
    public static byte[] encrypt(Key key, byte[] content, int inputOffset, int inputLen) throws Exception {
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] encrypted = cipher.doFinal(content, inputOffset, inputLen);
        byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
        return result;
    }

    /**
     * 解码 Base64 并使用 AES-GCM 解密。
     *
     * @param key
     * @param result
     * @return
     * @throws Exception
     */
    public static byte[] decryptBase64(Key key, String result) throws Exception {
        byte[] decode = Base64.getDecoder().decode(result);
        return decrypt(key, decode);
    }

    /**
     * 使用 AES-GCM 解密并校验认证标签。
     *
     * @param key
     * @param result
     * @return
     * @throws Exception
     */
    public static byte[] decrypt(Key key, byte[] result) throws Exception {
        if (result.length < GCM_NONCE_BYTES + GCM_TAG_BITS / 8) {
            throw new IllegalArgumentException("Encrypted frame is too short");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_BITS, result, 0, GCM_NONCE_BYTES));
        return cipher.doFinal(result, GCM_NONCE_BYTES, result.length - GCM_NONCE_BYTES);
    }

}
