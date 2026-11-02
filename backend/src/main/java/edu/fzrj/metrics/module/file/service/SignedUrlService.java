package edu.fzrj.metrics.module.file.service;

import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 签名地址：LOCAL 使用后端 HMAC raw 地址，MINIO 使用 S3 预签名 GET。
 */
@Service
public class SignedUrlService {

    private final String signSecret;
    private final long expireMinutes;
    private final FileInfoMapper fileInfoMapper;
    private final ObjectProvider<MinioStorageService> minioStorageProvider;

    @Autowired
    public SignedUrlService(@Value("${app.file.sign-secret}") String signSecret,
                            @Value("${app.file.signed-url-expire-minutes}") long expireMinutes,
                            FileInfoMapper fileInfoMapper,
                            ObjectProvider<MinioStorageService> minioStorageProvider) {
        this.signSecret = requireStrongSecret(signSecret);
        this.expireMinutes = expireMinutes;
        this.fileInfoMapper = fileInfoMapper;
        this.minioStorageProvider = minioStorageProvider;
    }

    private static String requireStrongSecret(String secret) {
        String value = secret == null ? "" : secret.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        boolean placeholder = lower.contains("please-change-me") || lower.contains("change-me")
                || lower.contains("changeme") || lower.contains("replace-me")
                || lower.contains("your-secret") || lower.contains("placeholder")
                || lower.startsWith("${");
        if (value.getBytes(StandardCharsets.UTF_8).length < 32 || placeholder) {
            throw new IllegalStateException("文件签名密钥缺失、过短或仍为占位值，请通过 FILE_SIGN_SECRET 配置至少 32 字节的随机密钥");
        }
        return value;
    }

    public SignedUrlService(String signSecret, long expireMinutes) {
        this(signSecret, expireMinutes, null, null);
    }

    /** 生成签名地址；导出等只有 fileId 的场景按文件实际存储类型选择。 */
    public SignedUrl sign(Long fileId) {
        FileInfo fileInfo = fileInfoMapper == null ? null : fileInfoMapper.selectById(fileId);
        return fileInfo == null ? signLocal(fileId, true) : sign(fileInfo, true);
    }

    public SignedUrl sign(FileInfo fileInfo) {
        return sign(fileInfo, false);
    }

    public SignedUrl sign(FileInfo fileInfo, boolean download) {
        if ("MINIO".equals(fileInfo.getStorageType()) && minioStorageProvider != null) {
            MinioStorageService minio = minioStorageProvider.getIfAvailable();
            if (minio != null) {
                return download
                        ? minio.presignGet(fileInfo.getStorageKey(), expireMinutes, fileInfo.getFileName(), true)
                        : minio.presignGet(fileInfo.getStorageKey(), expireMinutes);
            }
        }
        return signLocal(fileInfo.getId(), download);
    }

    private SignedUrl signLocal(Long fileId, boolean download) {
        long expires = Instant.now().plusSeconds(expireMinutes * 60).getEpochSecond();
        String signature = hmac(fileId + ":" + expires);
        String url = "/api/v1/files/raw/" + fileId + "?e=" + expires + "&s=" + signature
                + (download ? "&dl=1" : "");
        LocalDateTime expiresAt = LocalDateTime.ofEpochSecond(expires, 0, ZoneOffset.of("+08:00"));
        return new SignedUrl(url, expiresAt);
    }

    /** 校验 LOCAL 后端签名；过期或签名不符返回 false */
    public boolean verify(Long fileId, long expires, String signature) {
        if (expires < Instant.now().getEpochSecond()) {
            return false;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = hmac(fileId + ":" + expires);
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
