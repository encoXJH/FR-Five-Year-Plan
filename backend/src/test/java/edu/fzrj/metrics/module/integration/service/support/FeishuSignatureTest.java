package edu.fzrj.metrics.module.integration.service.support;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书事件验签与加解密纯逻辑单测（签名算法见 FeishuSignature 类注释的官方文档链接）
 */
class FeishuSignatureTest {

    private static final String ENCRYPT_KEY = "test-encrypt-key-0123456789";

    @Nested
    class SignVerify {

        @Test
        void 签名与验签一致() {
            String body = "{\"event_id\":\"evt-1\"}";
            String signature = FeishuSignature.sign("1700000000", "nonce-1", ENCRYPT_KEY, body);
            assertEquals(64, signature.length());
            assertTrue(FeishuSignature.verify("1700000000", "nonce-1", ENCRYPT_KEY, body, signature));
        }

        @Test
        void 报文被篡改验签失败() {
            String signature = FeishuSignature.sign("1700000000", "nonce-1", ENCRYPT_KEY, "{\"a\":1}");
            assertFalse(FeishuSignature.verify("1700000000", "nonce-1", ENCRYPT_KEY, "{\"a\":2}", signature));
        }

        @Test
        void EncryptKey错误验签失败() {
            String body = "{\"event_id\":\"evt-1\"}";
            String signature = FeishuSignature.sign("1700000000", "nonce-1", ENCRYPT_KEY, body);
            assertFalse(FeishuSignature.verify("1700000000", "nonce-1", "other-key", body, signature));
        }

        @Test
        void 任意参数为空验签失败不抛异常() {
            assertFalse(FeishuSignature.verify(null, "n", ENCRYPT_KEY, "b", "s"));
            assertFalse(FeishuSignature.verify("t", null, ENCRYPT_KEY, "b", "s"));
            assertFalse(FeishuSignature.verify("t", "n", null, "b", "s"));
            assertFalse(FeishuSignature.verify("t", "n", ENCRYPT_KEY, null, "s"));
            assertFalse(FeishuSignature.verify("t", "n", ENCRYPT_KEY, "b", null));
        }

        @Test
        void 签名大写十六进制也可通过() {
            String body = "{\"event_id\":\"evt-1\"}";
            String signature = FeishuSignature.sign("1700000000", "nonce-1", ENCRYPT_KEY, body);
            assertTrue(FeishuSignature.verify("1700000000", "nonce-1", ENCRYPT_KEY, body, signature.toUpperCase()));
        }
    }

    @Nested
    class Cipher {

        @Test
        void 加解密往返一致() {
            String plain = "{\"schema\":\"2.0\",\"header\":{\"event_id\":\"evt-1\"},\"挑战\":\"中文内容\"}";
            String encrypted = FeishuSignature.encrypt(ENCRYPT_KEY, plain);
            assertNotEquals(plain, encrypted);
            assertEquals(plain, FeishuSignature.decrypt(ENCRYPT_KEY, encrypted));
        }

        @Test
        void 错误密钥解密失败() {
            String encrypted = FeishuSignature.encrypt(ENCRYPT_KEY, "{\"a\":1}");
            assertThrows(IllegalArgumentException.class,
                    () -> FeishuSignature.decrypt("wrong-key", encrypted));
        }

        @Test
        void 非法密文解密失败() {
            assertThrows(IllegalArgumentException.class,
                    () -> FeishuSignature.decrypt(ENCRYPT_KEY, "not-valid-base64!!!"));
        }
    }

    @Nested
    class IsEncryptedBody {

        @Test
        void 识别加密报文() {
            assertTrue(FeishuSignature.isEncryptedBody("{\"encrypt\":\"abc\"}"));
            assertFalse(FeishuSignature.isEncryptedBody("{\"event_id\":\"evt-1\"}"));
            assertFalse(FeishuSignature.isEncryptedBody(null));
        }
    }
}
