package edu.fzrj.metrics.module.file.service;

import edu.fzrj.metrics.module.file.entity.FileUploadSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** S3 multipart 浏览器直传操作。 */
@Service
@ConditionalOnProperty(name = "app.file.storage-type", havingValue = "MINIO")
public class DirectStorageService {

    private final MinioStorageService storage;
    private final long expireMinutes;

    public DirectStorageService(MinioStorageService storage,
                                @Value("${app.file.signed-url-expire-minutes}") long expireMinutes) {
        this.storage = storage;
        this.expireMinutes = expireMinutes;
    }

    public String createMultipart(FileUploadSession session) {
        return storage.s3Client().createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(storage.bucket())
                .key(session.getStorageKey())
                .contentType(session.getMimeType())
                .build()).uploadId();
    }

    public SignedUrlService.SignedUrl presignPart(FileUploadSession session, int partNumber) {
        Duration duration = Duration.ofMinutes(expireMinutes);
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(storage.bucket())
                .key(session.getStorageKey())
                .uploadId(session.getRemoteUploadId())
                .partNumber(partNumber)
                .build();
        String url = storage.presigner().presignUploadPart(UploadPartPresignRequest.builder()
                .signatureDuration(duration)
                .uploadPartRequest(request)
                .build()).url().toString();
        Instant expires = Instant.now().plus(duration);
        return new SignedUrlService.SignedUrl(url,
                LocalDateTime.ofInstant(expires, ZoneOffset.of("+08:00")));
    }

    public List<DirectPart> listParts(FileUploadSession session) {
        List<DirectPart> result = new ArrayList<>();
        Integer marker = null;
        do {
            var response = storage.s3Client().listParts(ListPartsRequest.builder()
                    .bucket(storage.bucket())
                    .key(session.getStorageKey())
                    .uploadId(session.getRemoteUploadId())
                    .partNumberMarker(marker)
                    .build());
            for (Part part : response.parts()) {
                result.add(new DirectPart(part.partNumber(), part.eTag()));
            }
            marker = response.isTruncated() ? response.nextPartNumberMarker() : null;
        } while (marker != null);
        return result;
    }

    public void complete(FileUploadSession session, List<DirectPart> parts) {
        List<CompletedPart> completed = parts.stream()
                .map(part -> CompletedPart.builder().partNumber(part.partNumber()).eTag(part.eTag()).build())
                .toList();
        storage.s3Client().completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(storage.bucket())
                .key(session.getStorageKey())
                .uploadId(session.getRemoteUploadId())
                .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                .build());
    }

    public long sizeOf(String storageKey) {
        return storage.s3Client().headObject(HeadObjectRequest.builder()
                .bucket(storage.bucket())
                .key(storageKey)
                .build()).contentLength();
    }

    public InputStream read(String storageKey) throws IOException {
        return storage.read(storageKey);
    }

    public void delete(String storageKey) throws IOException {
        storage.delete(storageKey);
    }

    public record DirectPart(int partNumber, String eTag) {
    }
}
