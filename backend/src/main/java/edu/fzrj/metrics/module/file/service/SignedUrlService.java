package edu.fzrj.metrics.module.file.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 签名地址：HMAC-SHA256(fileId:expires, signSecret)，默认 10 分钟有效（API 约定 8.6）
 */
@Service
public class SignedUrlService {

    private final String signSecret;
    private final long expireMinutes;

    public SignedUrlService(@Value("${app.file.sign-secret}") String signSecret,
                            @Value("${app.file.signed-url-expire-minutes}") long expireMinutes) {
        this.signSecret = signSecret;
        this.expireMinutes = expireMinutes;
    }

    /** 生成签名地址（相对路径） */
    public SignedUrl sign(Long fileId) {
        long expires = Instant.now().plusSeconds(expireMinutes * 60).getEpochSecond();
        String signature = hmac(fileId + ":" + expires);
        String url = "/api/v1/files/raw/" + fileId + "?e=" + expires + "&s=" + signature;
        LocalDateTime expiresAt = LocalDateTime.ofEpochSecond(expires, 0, ZoneOffset.of("+08:00"));
        return new SignedUrl(url, expiresAt);
    }

    /** 校验签名；过期或签名不符返回 false */
    public boolean verify(Long fileId, long expires, String signature) {
        if (expires < Instant.now().getEpochSecond()) {
            return false;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = hmac(fileId + ":" + expires);
        // 常量时间比较
        return MessageDigestUtils.isEqual(expected, signature);
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record SignedUrl(String url, LocalDateTime expiresAt) {
    }

    /** 常量时间字符串比较 */
    static final class MessageDigestUtils {
        static boolean isEqual(String a, String b) {
            byte[] ba = a.getBytes(StandardCharsets.UTF_8);
            byte[] bb = b.getBytes(StandardCharsets.UTF_8);
            return java.security.MessageDigest.isEqual(ba, bb);
        }
    }
}
