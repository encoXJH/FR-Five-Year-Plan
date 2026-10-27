package edu.fzrj.metrics.module.file.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignedUrlServiceTest {

    private final SignedUrlService service = new SignedUrlService("test-sign-secret", 10);

    @Test
    void 签名可校验通过() {
        SignedUrlService.SignedUrl signed = service.sign(123L);
        assertNotNull(signed.url());
        // 从 url 中拆出 e 和 s
        String query = signed.url().substring(signed.url().indexOf('?') + 1);
        long e = Long.parseLong(query.split("&")[0].split("=")[1]);
        String s = query.split("&")[1].split("=")[1];
        assertTrue(service.verify(123L, e, s));
    }

    @Test
    void 过期签名不通过() {
        long past = java.time.Instant.now().minusSeconds(60).getEpochSecond();
        String s = "whatever";
        assertFalse(service.verify(123L, past, s));
    }

    @Test
    void 篡改签名不通过() {
        SignedUrlService.SignedUrl signed = service.sign(123L);
        String query = signed.url().substring(signed.url().indexOf('?') + 1);
        long e = Long.parseLong(query.split("&")[0].split("=")[1]);
        assertFalse(service.verify(123L, e, "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    void 不同文件id签名不通过() {
        SignedUrlService.SignedUrl signed = service.sign(123L);
        String query = signed.url().substring(signed.url().indexOf('?') + 1);
        long e = Long.parseLong(query.split("&")[0].split("=")[1]);
        String s = query.split("&")[1].split("=")[1];
        assertFalse(service.verify(456L, e, s));
    }
}
