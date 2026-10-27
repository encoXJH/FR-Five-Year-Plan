package edu.fzrj.metrics.module.integration.service.support;

import edu.fzrj.metrics.common.util.Sha256;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 飞书事件回调验签与报文解密（纯逻辑，便于单测）。
 *
 * <p>官方文档：
 * <ul>
 *   <li>签名校验（sha256(timestamp + nonce + encryptKey + body) 十六进制）：
 *   https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription-configure-/request-url-configuration-case</li>
 *   <li>Encrypt Key 加密方案（AES-256-CBC，key = SHA-256(encryptKey)，IV 取 key 前 16 字节）：
 *   https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription-configure-/encrypt-key-encryption-configuration-case</li>
 * </ul>
 */
public final class FeishuSignature {

    private FeishuSignature() {
    }

    /** 计算签名：sha256(timestamp + nonce + encryptKey + body) 十六进制小写 */
    public static String sign(String timestamp, String nonce, String encryptKey, String body) {
        return Sha256.hex(timestamp + nonce + encryptKey + body);
    }

    /** 验签（常量时间比较，防时序攻击） */
    public static boolean verify(String timestamp, String nonce, String encryptKey, String body, String signature) {
        if (timestamp == null || nonce == null || encryptKey == null || body == null || signature == null) {
            return false;
        }
        String expected = sign(timestamp, nonce, encryptKey, body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** 解密飞书加密事件报文中的 encrypt 字段（AES-256-CBC / PKCS5，key = SHA-256(encryptKey)） */
    public static String decrypt(String encryptKey, String encryptBase64) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keyOf(encryptKey), ivOf(encryptKey));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encryptBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("飞书事件报文解密失败", e);
        }
    }

    /** 加密（仅供测试与调试自加密） */
    public static String encrypt(String encryptKey, String plainText) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keyOf(encryptKey), ivOf(encryptKey));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("飞书事件报文加密失败", e);
        }
    }

    /** 事件体是否为加密报文（{"encrypt": "..."}） */
    public static boolean isEncryptedBody(String body) {
        return body != null && body.contains("\"encrypt\"");
    }

    private static SecretKeySpec keyOf(String encryptKey) {
        return new SecretKeySpec(sha256Bytes(encryptKey), "AES");
    }

    private static IvParameterSpec ivOf(String encryptKey) {
        byte[] key = sha256Bytes(encryptKey);
        byte[] iv = new byte[16];
        System.arraycopy(key, 0, iv, 0, 16);
        return new IvParameterSpec(iv);
    }

    private static byte[] sha256Bytes(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
