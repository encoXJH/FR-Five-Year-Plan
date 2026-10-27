package edu.fzrj.metrics.module.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.Sha256;
import edu.fzrj.metrics.module.file.dto.FileInfoResponse;
import edu.fzrj.metrics.module.file.dto.MultipartInitRequest;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileUploadSession;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileUploadSessionMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文件：直接上传、分片上传、秒传、签名地址（API 约定第 8 章）
 */
@Slf4j
@Service
public class FileService {

    private static final Set<String> BIZ_TYPES = Set.of(
            "SUBMISSION_ATTACHMENT", "ADJUSTMENT_ATTACHMENT", "MILESTONE_ATTACHMENT", "IMPORT_FILE");
    private static final int PART_SIZE = 5 * 1024 * 1024;

    private final FileInfoMapper fileInfoMapper;
    private final FileUploadSessionMapper sessionMapper;
    private final SysUserMapper sysUserMapper;
    private final StorageService storageService;
    private final FileTypeRegistry typeRegistry;
    private final ObjectMapper objectMapper;

    @Value("${app.file.storage-type:LOCAL}")
    private String storageType;

    public FileService(FileInfoMapper fileInfoMapper, FileUploadSessionMapper sessionMapper,
                       SysUserMapper sysUserMapper, StorageService storageService,
                       FileTypeRegistry typeRegistry, ObjectMapper objectMapper) {
        this.fileInfoMapper = fileInfoMapper;
        this.sessionMapper = sessionMapper;
        this.sysUserMapper = sysUserMapper;
        this.storageService = storageService;
        this.typeRegistry = typeRegistry;
        this.objectMapper = objectMapper;
    }

    // ---------------- 直接上传 ----------------

    @Transactional
    public FileInfoResponse upload(MultipartFile file, String bizType) {
        checkBizType(bizType);
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "unnamed";
        String ext = extOf(originalName);
        String category = checkExt(ext);
        if (file.getSize() > typeRegistry.maxSizeOf(category)) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        try (InputStream in = file.getInputStream()) {
            if (!typeRegistry.magicMatch(ext, in)) {
                throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED, "文件内容与扩展名不符");
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取文件失败");
        }
        return store(file::getInputStream, file.getSize(), originalName, ext, category,
                file.getContentType(), bizType);
    }

    // ---------------- 分片上传 ----------------

    @Transactional
    public InitResult initMultipart(MultipartInitRequest request) {
        checkBizType(request.getBizType());
        String ext = extOf(request.getFileName());
        String category = checkExt(ext);
        if (request.getSize() > typeRegistry.maxSizeOf(category)) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        // 秒传
        FileInfo dup = findBySha256(request.getSha256());
        if (dup != null) {
            return new InitResult(null, 0, 0, toResponse(dup));
        }
        int partCount = (int) ((request.getSize() + PART_SIZE - 1) / PART_SIZE);
        FileUploadSession session = new FileUploadSession();
        session.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        session.setFileName(request.getFileName());
        session.setSize(request.getSize());
        session.setMimeType(request.getMimeType());
        session.setSha256(request.getSha256().toLowerCase());
        session.setPartSize(PART_SIZE);
        session.setPartCount(partCount);
        session.setUploadedParts("[]");
        session.setStatus("UPLOADING");
        session.setExpiresAt(LocalDateTime.now().plusHours(24));
        session.setCreatedBy(SecurityUtils.getUserId());
        sessionMapper.insert(session);
        return new InitResult(session.getUploadId(), PART_SIZE, partCount, null);
    }

    @Transactional
    public void uploadPart(String uploadId, int partNumber, InputStream content) {
        FileUploadSession session = requireSession(uploadId);
        if (partNumber < 1 || partNumber > session.getPartCount()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分片编号超出范围");
        }
        try {
            Path partDir = storageService.partDir(uploadId);
            Files.createDirectories(partDir);
            try (InputStream in = content) {
                Files.copy(in, partDir.resolve(String.valueOf(partNumber)),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            List<Integer> uploaded = readUploadedParts(session);
            if (!uploaded.contains(partNumber)) {
                uploaded.add(partNumber);
                sessionMapper.update(null, new LambdaUpdateWrapper<FileUploadSession>()
                        .eq(FileUploadSession::getId, session.getId())
                        .set(FileUploadSession::getUploadedParts,
                                objectMapper.writeValueAsString(uploaded)));
            }
        } catch (IOException e) {
            log.error("分片写入失败 uploadId={} part={}", uploadId, partNumber, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "分片写入失败");
        }
    }

    public SessionInfo getSession(String uploadId) {
        FileUploadSession session = requireSession(uploadId);
        return new SessionInfo(session.getUploadId(), session.getPartSize(),
                session.getPartCount(), readUploadedParts(session));
    }

    @Transactional
    public FileInfoResponse completeMultipart(String uploadId) {
        FileUploadSession session = requireSession(uploadId);
        List<Integer> uploaded = readUploadedParts(session);
        if (uploaded.size() != session.getPartCount()) {
            throw new BizException(ErrorCode.PART_INVALID, "分片缺失，无法合并");
        }
        // 秒传兜底（并发上传同一文件）
        FileInfo dup = findBySha256(session.getSha256());
        if (dup != null) {
            finishSession(session);
            return toResponse(dup);
        }
        String ext = extOf(session.getFileName());
        String category = checkExt(ext);
        Path partDir = storageService.partDir(uploadId);
        for (int i = 1; i <= session.getPartCount(); i++) {
            if (!Files.exists(partDir.resolve(String.valueOf(i)))) {
                throw new BizException(ErrorCode.PART_INVALID, "分片 " + i + " 缺失");
            }
        }
        FileInfoResponse response = store(() -> new SequenceInputStream(
                        Collections.enumeration(partStreams(partDir, session.getPartCount()))),
                session.getSize(), session.getFileName(), ext, category,
                session.getMimeType(), null, session.getSha256(), session.getCreatedBy());
        finishSession(session);
        try {
            storageService.deletePartDir(uploadId);
        } catch (IOException e) {
            log.warn("清理分片目录失败 uploadId={}", uploadId, e);
        }
        return response;
    }

    private List<InputStream> partStreams(Path partDir, int partCount) {
        List<InputStream> streams = new ArrayList<>();
        try {
            for (int i = 1; i <= partCount; i++) {
                streams.add(Files.newInputStream(partDir.resolve(String.valueOf(i))));
            }
            return streams;
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取分片失败");
        }
    }

    private void finishSession(FileUploadSession session) {
        sessionMapper.update(null, new LambdaUpdateWrapper<FileUploadSession>()
                .eq(FileUploadSession::getId, session.getId())
                .set(FileUploadSession::getStatus, "COMPLETED"));
    }

    // ---------------- 查询 / 原始流 ----------------

    public FileInfo requireFile(Long fileId) {
        FileInfo fileInfo = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getId, fileId)
                .eq(FileInfo::getDeleted, 0L));
        if (fileInfo == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return fileInfo;
    }

    public FileInfoResponse detail(Long fileId) {
        return toResponse(requireFile(fileId));
    }

    // ---------------- 内部 ----------------

    @FunctionalInterface
    private interface InputStreamSupplier {
        InputStream get() throws IOException;
    }

    /** 落盘 + 入库；expectedSha256 非空时校验整文件哈希 */
    private FileInfoResponse store(InputStreamSupplier streamSupplier, long size, String fileName,
                                   String ext, String category, String mimeType, String bizType) {
        return store(streamSupplier, size, fileName, ext, category, mimeType, bizType,
                null, SecurityUtils.getUserId());
    }

    private FileInfoResponse store(InputStreamSupplier streamSupplier, long size, String fileName,
                                   String ext, String category, String mimeType, String bizType,
                                   String expectedSha256, Long operatorId) {
        String storageKey = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String sha256;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(streamSupplier.get(), digest)) {
                storageService.save(in, storageKey);
            }
            sha256 = toHex(digest.digest());
        } catch (Exception e) {
            log.error("文件落盘失败", e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件保存失败");
        }
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(sha256)) {
            try {
                storageService.delete(storageKey);
            } catch (IOException ignored) {
            }
            throw new BizException(ErrorCode.PART_INVALID, "整文件 SHA-256 校验失败");
        }
        // 秒传
        FileInfo dup = findBySha256(sha256);
        if (dup != null) {
            try {
                storageService.delete(storageKey);
            } catch (IOException ignored) {
            }
            return toResponse(dup);
        }
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileName);
        fileInfo.setExt(ext);
        fileInfo.setSize(size);
        fileInfo.setMimeType(mimeType);
        fileInfo.setCategory(category);
        fileInfo.setBizType(bizType);
        fileInfo.setStatus("TEMP");
        fileInfo.setStorageType(storageType);
        fileInfo.setStorageKey(storageKey);
        fileInfo.setSha256(sha256);
        fileInfo.setTranscodeStatus("VIDEO".equals(category) ? "PROCESSING" : null);
        fileInfo.setUploadedBy(operatorId);
        fileInfo.setUploadedAt(LocalDateTime.now());
        fileInfo.setCreatedBy(operatorId);
        fileInfoMapper.insert(fileInfo);
        return toResponse(fileInfo);
    }

    private FileInfo findBySha256(String sha256) {
        if (!StringUtils.hasText(sha256)) {
            return null;
        }
        List<FileInfo> list = fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getSha256, sha256.toLowerCase())
                .eq(FileInfo::getDeleted, 0L)
                .orderByDesc(FileInfo::getId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private FileUploadSession requireSession(String uploadId) {
        FileUploadSession session = sessionMapper.selectOne(new LambdaQueryWrapper<FileUploadSession>()
                .eq(FileUploadSession::getUploadId, uploadId));
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "上传会话不存在");
        }
        if (!"UPLOADING".equals(session.getStatus()) || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.NOT_FOUND, "上传会话已过期");
        }
        return session;
    }

    private List<Integer> readUploadedParts(FileUploadSession session) {
        try {
            return objectMapper.readValue(session.getUploadedParts(), new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void checkBizType(String bizType) {
        if (bizType == null || !BIZ_TYPES.contains(bizType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "bizType 不合法");
        }
    }

    private String checkExt(String ext) {
        String category = typeRegistry.categoryOf(ext);
        if (category == null) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        }
        return category;
    }

    private String extOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    public FileInfoResponse toResponse(FileInfo fileInfo) {
        String uploaderName = null;
        if (fileInfo.getUploadedBy() != null) {
            SysUser uploader = sysUserMapper.selectById(fileInfo.getUploadedBy());
            uploaderName = uploader == null ? null : uploader.getName();
        }
        return new FileInfoResponse(fileInfo.getId(), fileInfo.getFileName(), fileInfo.getSize(),
                fileInfo.getMimeType(), fileInfo.getCategory(), fileInfo.getBizType(),
                fileInfo.getStatus(), fileInfo.getTranscodeStatus(), fileInfo.getUploadedAt(),
                new FileInfoResponse.UserRef(fileInfo.getUploadedBy(), uploaderName));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ---------------- 结果对象 ----------------

    public record InitResult(String uploadId, Integer partSize, Integer partCount,
                             FileInfoResponse fileInfo) {
    }

    public record SessionInfo(String uploadId, Integer partSize, Integer partCount,
                              List<Integer> uploadedParts) {
    }
}
