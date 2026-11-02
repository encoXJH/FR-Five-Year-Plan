package edu.fzrj.metrics.module.file.service;

import edu.fzrj.metrics.module.file.entity.FileInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignedUrlServiceTest {

    private static final String SECRET = "test-file-sign-secret-0123456789abcdef0123456789abcdef";

    private final SignedUrlService service = new SignedUrlService(SECRET, 10);

    @Test
    void 缺失过短或占位密钥拒绝初始化() {
        String[] invalidSecrets = {null, "", " ", "short",
                "please-change-me-file-sign-secret-with-enough-length", "${FILE_SIGN_SECRET}"};
        for (String secret : invalidSecrets) {
            assertThrows(IllegalStateException.class, () -> new SignedUrlService(secret, 10));
        }
    }

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
    void LOCAL下载地址带附件标记而预览地址不带() {
        FileInfo file = storedFile("LOCAL");
        assertTrue(service.sign(file, true).url().endsWith("&dl=1"));
        assertFalse(service.sign(file, false).url().contains("&dl=1"));
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

    @Test
    @SuppressWarnings("unchecked")
    void MINIO文件使用对象存储预签名地址() {
        ObjectProvider<MinioStorageService> provider = mock(ObjectProvider.class);
        MinioStorageService minio = mock(MinioStorageService.class);
        when(provider.getIfAvailable()).thenReturn(minio);
        SignedUrlService.SignedUrl expected = new SignedUrlService.SignedUrl(
                "http://localhost:9000/bucket/key?X-Amz-Signature=abc", LocalDateTime.now().plusMinutes(10));
        when(minio.presignGet("2026/10/a.pdf", 10)).thenReturn(expected);
        SignedUrlService choice = new SignedUrlService(SECRET, 10, null, provider);
        FileInfo file = storedFile("MINIO");

        assertSame(expected, choice.sign(file));
    }

    @Test
    @SuppressWarnings("unchecked")
    void MINIO下载地址携带附件响应头() {
        ObjectProvider<MinioStorageService> provider = mock(ObjectProvider.class);
        MinioStorageService minio = mock(MinioStorageService.class);
        when(provider.getIfAvailable()).thenReturn(minio);
        SignedUrlService.SignedUrl expected = new SignedUrlService.SignedUrl(
                "http://localhost:9000/bucket/key?response-content-disposition=attachment", LocalDateTime.now());
        when(minio.presignGet("2026/10/a.pdf", 10, "a.pdf", true)).thenReturn(expected);
        SignedUrlService choice = new SignedUrlService(SECRET, 10, null, provider);

        assertSame(expected, choice.sign(storedFile("MINIO"), true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 切换MINIO后历史LOCAL文件仍使用后端raw签名() {
        ObjectProvider<MinioStorageService> provider = mock(ObjectProvider.class);
        SignedUrlService choice = new SignedUrlService(SECRET, 10, null, provider);

        SignedUrlService.SignedUrl signed = choice.sign(storedFile("LOCAL"));

        assertTrue(signed.url().startsWith("/api/v1/files/raw/123?"));
        verify(provider, never()).getIfAvailable();
    }

    private static FileInfo storedFile(String storageType) {
        FileInfo file = new FileInfo();
        file.setId(123L);
        file.setStorageType(storageType);
        file.setStorageKey("2026/10/a.pdf");
        file.setFileName("a.pdf");
        return file;
    }
}
