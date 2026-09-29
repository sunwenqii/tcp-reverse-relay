package dev.sunwenqi.tcpreverserelay.utils;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.security.Key;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AESUtilTest {
    @Test
    void eachFrameHasFreshNonceAndTamperingFails() throws Exception {
        Key key = AESUtil.createKey(128);
        byte[] plain = "repeatable message".getBytes();
        byte[] first = AESUtil.encrypt(key, plain);
        byte[] second = AESUtil.encrypt(key, plain);

        assertFalse(Arrays.equals(first, second));
        assertArrayEquals(plain, AESUtil.decrypt(key, first));
        assertArrayEquals(plain, AESUtil.decrypt(key, second));

        first[first.length - 1] ^= 1;
        assertThrows(AEADBadTagException.class, () -> AESUtil.decrypt(key, first));
    }

    @Test
    void hmacDetectsChangedFields() {
        String signature = HmacSignature.sign("long secret", "1", "nonce", "UTF-8", "ciphertext");
        assertTrue(HmacSignature.verify(signature, HmacSignature.sign("long secret", "1", "nonce", "UTF-8", "ciphertext")));
        assertFalse(HmacSignature.verify(signature, HmacSignature.sign("long secret", "2", "nonce", "UTF-8", "ciphertext")));
    }
}
