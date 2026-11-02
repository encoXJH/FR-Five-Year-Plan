package edu.fzrj.metrics.module.file.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** MinIO 的 S3 协议实现。 */
@Service
@Primary
@ConditionalOnProperty(name = "app.file.storage-type", havingValue = "MINIO")
public class MinioStorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;

    public MinioStorageService(
            @Value("${app.file.minio.endpoint}") String endpoint,
            @Value("${app.file.minio.public-endpoint:}") String publicEndpoint,
            @Value("${app.file.minio.region}") String region,
            @Value("${app.file.minio.access-key}") String accessKey,
            @Value("${app.file.minio.secret-key}") String secretKey,
            @Value("${app.file.minio.bucket}") String bucket,
            @Value("${app.file.minio.path-style-access:true}") boolean pathStyleAccess) {
        if (!StringUtils.hasText(accessKey) || !StringUtils.hasText(secretKey)) {
            throw new IllegalStateException("MINIO_ACCESS_KEY 和 MINIO_SECRET_KEY 必须配置");
        }
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var configuration = S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build();
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(StringUtils.hasText(publicEndpoint) ? publicEndpoint : endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration)
                .build();
        this.bucket = bucket;
    }

    MinioStorageService(S3Client s3Client, S3Presigner presigner, String bucket) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public void save(InputStream in, String storageKey) throws IOException {
        try {
            byte[] content = in.readAllBytes();
            save(RequestBody.fromBytes(content), storageKey);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("写入 MinIO 失败", e);
        }
    }

    @Override
    public void save(InputStream in, String storageKey, long size) throws IOException {
        try {
            save(RequestBody.fromInputStream(in, size), storageKey);
        } catch (Exception e) {
            throw new IOException("写入 MinIO 失败", e);
        }
    }

    private void save(RequestBody body, String storageKey) {
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(storageKey).build(), body);
    }

    @Override
    public InputStream read(String storageKey) throws IOException {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (Exception e) {
            throw new IOException("读取 MinIO 文件失败", e);
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (Exception e) {
            throw new IOException("删除 MinIO 文件失败", e);
        }
    }

    public SignedUrlService.SignedUrl presignGet(String storageKey, long expireMinutes) {
        return presignGet(storageKey, expireMinutes, null, false);
    }

    public SignedUrlService.SignedUrl presignGet(String storageKey, long expireMinutes,
                                                  String fileName, boolean download) {
        Duration duration = Duration.ofMinutes(expireMinutes);
        GetObjectRequest.Builder requestBuilder = GetObjectRequest.builder().bucket(bucket).key(storageKey);
        if (download) {
            String encoded = URLEncoder.encode(fileName == null ? "download" : fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            requestBuilder.responseContentDisposition("attachment; filename*=UTF-8''" + encoded);
        }
        String url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(requestBuilder.build())
                .build()).url().toString();
        Instant expires = Instant.now().plus(duration);
        return new SignedUrlService.SignedUrl(url,
                LocalDateTime.ofInstant(expires, ZoneOffset.of("+08:00")));
    }

    S3Client s3Client() {
        return s3Client;
    }

    S3Presigner presigner() {
        return presigner;
    }

    String bucket() {
        return bucket;
    }

    @Override
    public Path partDir(String uploadId) {
        throw new UnsupportedOperationException("MINIO 分片由浏览器直传");
    }

    @Override
    public void deletePartDir(String uploadId) {
        // MINIO 不使用本地分片目录
    }
}
