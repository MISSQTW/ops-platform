package com.lqbz.ops.inspection;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {

    private final byte[] key;

    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${ops.inspection.master-key:}") String encoded) {
        try {
            key = com.lqbz.ops.compat.Java8.isBlank(encoded) ? null : Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("巡检主密钥必须为Base64编码的32字节密钥");
        }
        if (key != null && key.length != 32)
            throw new IllegalStateException("巡检主密钥必须为Base64编码的32字节密钥");
    }

    public boolean available() {
        return key != null;
    }

    private void requireKey() {
        if (!available())
            throw new IllegalStateException("请先配置 OPS_INSPECTION_MASTER_KEY 再保存深度巡检配置");
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception exception) {
            throw new IllegalStateException("巡检配置加密失败");
        }
    }

    public String decrypt(String ciphertext) {
        requireKey();
        try {
            byte[] encoded = Base64.getDecoder().decode(ciphertext);
            if (encoded.length < 28)
                throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, encoded, 0, 12));
            return new String(cipher.doFinal(encoded, 12, encoded.length - 12), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("巡检配置解密失败，请核对主密钥");
        }
    }

    public String credential(long assetId, long revision) {
        requireKey();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("ops-inspection-v1:" + assetId + ":" + revision).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("巡检探测凭据生成失败");
        }
    }
}
