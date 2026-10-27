package edu.fzrj.metrics.module.file.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.dto.FileInfoResponse;
import edu.fzrj.metrics.module.file.dto.MultipartInitRequest;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.service.FileService;
import edu.fzrj.metrics.module.file.service.SignedUrlService;
import edu.fzrj.metrics.module.file.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 文件接口（API 约定第 8 章）
 */
@Tag(name = "文件")
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;
    private final SignedUrlService signedUrlService;
    private final StorageService storageService;

    /** kkFileView 服务地址（未配置时 OFFICE 预览返回 url=null，由前端提示下载） */
    @Value("${app.file.kkfileview-url:}")
    private String kkFileViewUrl;

    public FileController(FileService fileService, SignedUrlService signedUrlService,
                          StorageService storageService) {
        this.fileService = fileService;
        this.signedUrlService = signedUrlService;
        this.storageService = storageService;
    }

    @Operation(summary = "直接上传（≤20MB 场景）")
    @PostMapping
    @AuditLog(action = "CREATE", resourceType = "file", resourceId = "#result.id")
    public FileInfoResponse upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam("bizType") String bizType) {
        return fileService.upload(file, bizType);
    }

    @Operation(summary = "分片上传初始化")
    @PostMapping("/multipart/init")
    public FileService.InitResult initMultipart(@Valid @RequestBody MultipartInitRequest request) {
        return fileService.initMultipart(request);
    }

    @Operation(summary = "上传分片（从 1 开始）")
    @PutMapping("/multipart/{uploadId}/parts/{partNumber}")
    public void uploadPart(@PathVariable String uploadId, @PathVariable int partNumber,
                           HttpServletRequest request) throws IOException {
        fileService.uploadPart(uploadId, partNumber, request.getInputStream());
    }

    @Operation(summary = "查询已上传分片（断点续传）")
    @GetMapping("/multipart/{uploadId}")
    public FileService.SessionInfo getSession(@PathVariable String uploadId) {
        return fileService.getSession(uploadId);
    }

    @Operation(summary = "合并分片并校验")
    @PostMapping("/multipart/{uploadId}/complete")
    @AuditLog(action = "CREATE", resourceType = "file", resourceId = "#result.id")
    public FileInfoResponse complete(@PathVariable String uploadId) {
        return fileService.completeMultipart(uploadId);
    }

    @Operation(summary = "文件详情")
    @GetMapping("/{fileId}")
    public FileInfoResponse detail(@PathVariable Long fileId) {
        return fileService.detail(fileId);
    }

    @Operation(summary = "预览地址（IMAGE / PDF / VIDEO 返回签名地址；OFFICE 走 kkFileView，未配置时 url 为 null）")
    @GetMapping("/{fileId}/preview")
    @AuditLog(action = "FILE_PREVIEW", resourceType = "file", resourceId = "#fileId")
    public PreviewResult preview(@PathVariable Long fileId) {
        FileInfo fileInfo = fileService.requireFile(fileId);
        String previewType = switch (fileInfo.getCategory()) {
            case "IMAGE" -> "IMAGE";
            case "PDF" -> "PDF";
            case "VIDEO" -> "VIDEO";
            case "DOCUMENT" -> "OFFICE";
            default -> "UNSUPPORTED";
        };
        if ("UNSUPPORTED".equals(previewType)) {
            return new PreviewResult(previewType, null, null);
        }
        SignedUrlService.SignedUrl signed = signedUrlService.sign(fileId);
        if ("OFFICE".equals(previewType)) {
            if (!StringUtils.hasText(kkFileViewUrl)) {
                return new PreviewResult(previewType, null, null);
            }
            String inner = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(signed.url().getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
            return new PreviewResult(previewType,
                    kkFileViewUrl + "/onlinePreview?url=" + inner, signed.expiresAt());
        }
        return new PreviewResult(previewType, signed.url(), signed.expiresAt());
    }

    @Operation(summary = "下载地址")
    @GetMapping("/{fileId}/download-url")
    @AuditLog(action = "FILE_DOWNLOAD", resourceType = "file", resourceId = "#fileId")
    public DownloadUrlResult downloadUrl(@PathVariable Long fileId) {
        fileService.requireFile(fileId);
        SignedUrlService.SignedUrl signed = signedUrlService.sign(fileId);
        return new DownloadUrlResult(signed.url(), signed.expiresAt());
    }

    @Operation(summary = "签名地址原始流（公开，10 分钟有效）")
    @GetMapping("/raw/{fileId}")
    public ResponseEntity<InputStreamResource> raw(@PathVariable Long fileId,
                                                   @RequestParam("e") long expires,
                                                   @RequestParam("s") String signature) throws IOException {
        if (!signedUrlService.verify(fileId, expires, signature)) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        FileInfo fileInfo = fileService.requireFile(fileId);
        InputStream in = storageService.read(fileInfo.getStorageKey());
        String encoded = URLEncoder.encode(fileInfo.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(fileInfo.getMimeType() != null
                        ? MediaType.parseMediaType(fileInfo.getMimeType())
                        : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encoded)
                .body(new InputStreamResource(in));
    }

    public record PreviewResult(String previewType, String url, LocalDateTime expiresAt) {
    }

    public record DownloadUrlResult(String url, LocalDateTime expiresAt) {
    }
}
