package edu.fzrj.metrics.module.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.Sha256;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentService;
import edu.fzrj.metrics.module.file.dto.FileInfoResponse;
import edu.fzrj.metrics.module.file.dto.MultipartInitRequest;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.entity.FileUploadSession;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.file.mapper.FileUploadSessionMapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.milestone.service.MilestoneService;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.service.SubmissionService;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 文件：直接上传、分片上传、秒传、签名地址（API 约定第 8 章）
 */
@Slf4j
@Service
public class FileService {

    private static final Set<String> BIZ_TYPES = Set.of(
            "SUBMISSION_ATTACHMENT", "ADJUSTMENT_ATTACHMENT", "MILESTONE_ATTACHMENT",
            "INDICATOR_ATTACHMENT", "IMPORT_FILE");
    private static final int PART_SIZE = 5 * 1024 * 1024;

    private final FileInfoMapper fileInfoMapper;
    private final FileUploadSessionMapper sessionMapper;
    private final SysUserMapper sysUserMapper;
    private final StorageService storageService;
    private final FileTypeRegistry typeRegistry;
    private final ObjectMapper objectMapper;
    private final FileRefMapper fileRefMapper;
    private final SubmissionService submissionService;
    private final AdjustmentService adjustmentService;
    private final MilestoneService milestoneService;
    private final IndicatorMapper indicatorMapper;
    private final TaskMapper taskMapper;
    private final ObjectProvider<DirectStorageService> directStorageProvider;

    @Value("${app.file.storage-type:LOCAL}")
    private String storageType;

    public FileService(FileInfoMapper fileInfoMapper, FileUploadSessionMapper sessionMapper,
                       SysUserMapper sysUserMapper, StorageService storageService,
                       FileTypeRegistry typeRegistry, ObjectMapper objectMapper,
                       FileRefMapper fileRefMapper, SubmissionService submissionService,
                       AdjustmentService adjustmentService, MilestoneService milestoneService,
                       IndicatorMapper indicatorMapper, TaskMapper taskMapper,
                       ObjectProvider<DirectStorageService> directStorageProvider) {
        this.fileInfoMapper = fileInfoMapper;
        this.sessionMapper = sessionMapper;
        this.sysUserMapper = sysUserMapper;
        this.storageService = storageService;
        this.typeRegistry = typeRegistry;
        this.objectMapper = objectMapper;
        this.fileRefMapper = fileRefMapper;
        this.submissionService = submissionService;
        this.adjustmentService = adjustmentService;
        this.milestoneService = milestoneService;
        this.indicatorMapper = indicatorMapper;
        this.taskMapper = taskMapper;
        this.directStorageProvider = directStorageProvider;
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
                StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/octet-stream",
                bizType);
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
        FileInfo dup = findBySha256(request.getSha256(), SecurityUtils.getUserId());
        if (dup != null) {
            return new InitResult(null, 0, 0, toResponse(dup), false);
        }
        int partCount = (int) ((request.getSize() + PART_SIZE - 1) / PART_SIZE);
        boolean directUpload = isMinio();
        FileUploadSession session = new FileUploadSession();
        session.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        session.setFileName(request.getFileName());
        session.setSize(request.getSize());
        session.setMimeType(request.getMimeType());
        session.setBizType(request.getBizType());
        session.setSha256(request.getSha256().toLowerCase());
        session.setStorageType(directUpload ? "MINIO" : "LOCAL");
        if (directUpload) {
            session.setStorageKey(newStorageKey(ext));
            session.setRemoteUploadId(requireDirectStorage().createMultipart(session));
        }
        session.setPartSize(PART_SIZE);
        session.setPartCount(partCount);
        session.setUploadedParts("[]");
        session.setStatus("UPLOADING");
        session.setExpiresAt(LocalDateTime.now().plusHours(24));
        session.setCreatedBy(SecurityUtils.getUserId());
        sessionMapper.insert(session);
        return new InitResult(session.getUploadId(), PART_SIZE, partCount, null, directUpload);
    }

    public SignedUrlService.SignedUrl partUploadUrl(String uploadId, int partNumber) {
        FileUploadSession session = requireSession(uploadId);
        checkPartNumber(session, partNumber);
        if (!isDirect(session)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "本地上传会话不使用预签名分片地址");
        }
        return requireDirectStorage().presignPart(session, partNumber);
    }

    @Transactional
    public void uploadPart(String uploadId, int partNumber, InputStream content) {
        FileUploadSession session = requireSession(uploadId);
        checkPartNumber(session, partNumber);
        if (isDirect(session)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "MINIO 分片必须通过预签名地址直传");
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
        List<Integer> uploaded = isDirect(session)
                ? requireDirectStorage().listParts(session).stream()
                        .map(DirectStorageService.DirectPart::partNumber).toList()
                : readUploadedParts(session);
        return new SessionInfo(session.getUploadId(), session.getPartSize(),
                session.getPartCount(), uploaded, isDirect(session));
    }

    @Transactional
    public FileInfoResponse completeMultipart(String uploadId) {
        FileUploadSession session = requireSession(uploadId);
        if (isDirect(session)) {
            return completeDirectMultipart(session);
        }
        List<Integer> uploaded = readUploadedParts(session);
        if (uploaded.size() != session.getPartCount()) {
            throw new BizException(ErrorCode.PART_INVALID, "分片缺失，无法合并");
        }
        String ext = extOf(session.getFileName());
        String category = checkExt(ext);
        Path partDir = storageService.partDir(uploadId);
        long actualSize = 0;
        for (int i = 1; i <= session.getPartCount(); i++) {
            Path part = partDir.resolve(String.valueOf(i));
            if (!Files.exists(part)) {
                throw new BizException(ErrorCode.PART_INVALID, "分片 " + i + " 缺失");
            }
            try {
                actualSize = Math.addExact(actualSize, Files.size(part));
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "读取分片大小失败");
            }
        }
        if (actualSize != session.getSize()) {
            throw new BizException(ErrorCode.PART_INVALID, "实际文件大小与声明不一致");
        }
        if (actualSize > typeRegistry.maxSizeOf(category)) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        try (InputStream in = new SequenceInputStream(
                Collections.enumeration(partStreams(partDir, session.getPartCount())))) {
            if (!typeRegistry.magicMatch(ext, in)) {
                throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED, "文件内容与扩展名不符");
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取分片失败");
        }
        FileInfoResponse response = store(() -> new SequenceInputStream(
                        Collections.enumeration(partStreams(partDir, session.getPartCount()))),
                actualSize, session.getFileName(), ext, category,
                session.getMimeType(), session.getBizType(), session.getSha256(), session.getCreatedBy());
        finishSession(session);
        try {
            storageService.deletePartDir(uploadId);
        } catch (IOException e) {
            log.warn("清理分片目录失败 uploadId={}", uploadId, e);
        }
        return response;
    }

    private FileInfoResponse completeDirectMultipart(FileUploadSession session) {
        DirectStorageService direct = requireDirectStorage();
        List<DirectStorageService.DirectPart> parts = direct.listParts(session);
        if (parts.size() != session.getPartCount()) {
            throw new BizException(ErrorCode.PART_INVALID, "分片缺失，无法完成上传");
        }
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).partNumber() != i + 1) {
                throw new BizException(ErrorCode.PART_INVALID, "分片 " + (i + 1) + " 缺失");
            }
        }
        String ext = extOf(session.getFileName());
        String category = checkExt(ext);
        direct.complete(session, parts);
        String sha256;
        try {
            long actualSize = direct.sizeOf(session.getStorageKey());
            if (actualSize != session.getSize()) {
                throw new BizException(ErrorCode.PART_INVALID, "实际文件大小与声明不一致");
            }
            if (actualSize > typeRegistry.maxSizeOf(category)) {
                throw new BizException(ErrorCode.FILE_TOO_LARGE);
            }
            try (InputStream in = direct.read(session.getStorageKey())) {
                if (!typeRegistry.magicMatch(ext, in)) {
                    throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED, "文件内容与扩展名不符");
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(direct.read(session.getStorageKey()), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            sha256 = toHex(digest.digest());
            if (!session.getSha256().equalsIgnoreCase(sha256)) {
                throw new BizException(ErrorCode.PART_INVALID, "整文件 SHA-256 校验失败");
            }
        } catch (BizException e) {
            deleteDirectQuietly(direct, session.getStorageKey());
            throw e;
        } catch (Exception e) {
            deleteDirectQuietly(direct, session.getStorageKey());
            log.error("读取 MINIO 直传文件失败 uploadId={}", session.getUploadId(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "直传文件校验失败");
        }
        FileInfo dup = findBySha256(sha256, session.getCreatedBy());
        if (dup != null) {
            deleteDirectQuietly(direct, session.getStorageKey());
            finishSession(session);
            return toResponse(dup);
        }
        FileInfoResponse response = insertFileInfo(session.getSize(), session.getFileName(), ext, category,
                session.getMimeType(), session.getBizType(), "MINIO", session.getStorageKey(), sha256,
                session.getCreatedBy());
        finishSession(session);
        return response;
    }

    private void deleteDirectQuietly(DirectStorageService direct, String storageKey) {
        try {
            direct.delete(storageKey);
        } catch (IOException e) {
            log.warn("清理校验失败的 MINIO 文件失败 key={}", storageKey, e);
        }
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
        return toResponse(requireReadable(fileId));
    }

    public FileInfo requireBindable(Long fileId, String bizType, Long bizId) {
        FileInfo fileInfo = requireFile(fileId);
        var user = SecurityUtils.getLoginUser();
        if (user.getRoles().contains("ADMIN")
                || Objects.equals(user.getUserId(), fileInfo.getUploadedBy())) {
            return fileInfo;
        }
        Long existing = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId)
                .eq(FileRef::getBizType, bizType)
                .eq(FileRef::getBizId, bizId));
        if (existing > 0) {
            return fileInfo;
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    public FileInfo requireReadable(Long fileId) {
        FileInfo fileInfo = requireFile(fileId);
        if ("TEMP".equals(fileInfo.getStatus())) {
            if (!Objects.equals(SecurityUtils.getUserId(), fileInfo.getUploadedBy())) {
                throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
            }
            return fileInfo;
        }
        List<FileRef> refs = fileRefMapper.selectList(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId));
        for (FileRef ref : refs) {
            try {
                requireRefAccess(ref, fileInfo);
                return fileInfo;
            } catch (BizException e) {
                if (e.getErrorCode() != ErrorCode.DATA_SCOPE_DENIED) {
                    throw e;
                }
            }
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    private void requireRefAccess(FileRef ref, FileInfo fileInfo) {
        switch (ref.getBizType()) {
            case "SUBMISSION" -> {
                Submission submission = submissionService.requireSubmission(ref.getBizId());
                submissionService.requireAccess(submission);
            }
            case "ADJUSTMENT" -> adjustmentService.requireAccess(
                    adjustmentService.requireAdjustment(ref.getBizId()));
            case "MILESTONE" -> milestoneService.requireAccess(
                    milestoneService.requireMilestone(ref.getBizId()));
            case "INDICATOR" -> requireIndicatorMaterialAccess(ref.getBizId(), fileInfo);
            default -> throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
    }

    private void requireIndicatorMaterialAccess(Long indicatorId, FileInfo fileInfo) {
        var user = SecurityUtils.getLoginUser();
        if (user.getRoles().contains("ADMIN") || user.getRoles().contains("LEADER")) {
            return;
        }
        Indicator indicator = indicatorMapper.selectById(indicatorId);
        if (indicator == null || indicator.getDeleted() != 0L) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        if (user.getRoles().contains("SCHOOL_OWNER")) {
            Indicator ownerIndicator = indicator;
            if (ownerIndicator.getParentId() != null) {
                ownerIndicator = indicatorMapper.selectById(ownerIndicator.getParentId());
            }
            if (ownerIndicator != null && Objects.equals(user.getUserId(), ownerIndicator.getOwnerId())) {
                return;
            }
        }
        if (user.getRoles().contains("UNIT_OWNER") && user.getUnitId() != null) {
            SysUser uploader = sysUserMapper.selectById(fileInfo.getUploadedBy());
            long taskCount = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                    .eq(Task::getTopIndicatorId, indicatorId)
                    .eq(Task::getUnitId, user.getUnitId())
                    .eq(Task::getDeleted, 0L));
            if (uploader != null && Objects.equals(user.getUnitId(), uploader.getUnitId()) && taskCount > 0) {
                return;
            }
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
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
        String storageKey = newStorageKey(ext);
        String sha256;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(streamSupplier.get(), digest)) {
                storageService.save(in, storageKey, size);
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
        FileInfo dup = findBySha256(sha256, operatorId);
        if (dup != null) {
            try {
                storageService.delete(storageKey);
            } catch (IOException ignored) {
            }
            return toResponse(dup);
        }
        return insertFileInfo(size, fileName, ext, category, mimeType, bizType,
                currentStorageType(), storageKey, sha256, operatorId);
    }

    private FileInfoResponse insertFileInfo(long size, String fileName, String ext, String category,
                                            String mimeType, String bizType, String fileStorageType,
                                            String storageKey, String sha256, Long operatorId) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileName);
        fileInfo.setExt(ext);
        fileInfo.setSize(size);
        fileInfo.setMimeType(mimeType);
        fileInfo.setCategory(category);
        fileInfo.setBizType(bizType);
        fileInfo.setStatus("TEMP");
        fileInfo.setStorageType(fileStorageType);
        fileInfo.setStorageKey(storageKey);
        fileInfo.setSha256(sha256);
        fileInfo.setTranscodeStatus("VIDEO".equals(category) ? "SUCCEEDED" : null);
        fileInfo.setUploadedBy(operatorId);
        fileInfo.setUploadedAt(LocalDateTime.now());
        fileInfo.setCreatedBy(operatorId);
        fileInfoMapper.insert(fileInfo);
        return toResponse(fileInfo);
    }

    private String newStorageKey(String ext) {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
    }

    private boolean isMinio() {
        return "MINIO".equalsIgnoreCase(storageType);
    }

    private String currentStorageType() {
        return isMinio() ? "MINIO" : "LOCAL";
    }

    private boolean isDirect(FileUploadSession session) {
        return "MINIO".equals(session.getStorageType());
    }

    private DirectStorageService requireDirectStorage() {
        DirectStorageService direct = directStorageProvider == null ? null : directStorageProvider.getIfAvailable();
        if (direct == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "MINIO 直传存储未配置");
        }
        return direct;
    }

    private void checkPartNumber(FileUploadSession session, int partNumber) {
        if (partNumber < 1 || partNumber > session.getPartCount()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分片编号超出范围");
        }
    }

    private FileInfo findBySha256(String sha256, Long uploadedBy) {
        if (!StringUtils.hasText(sha256)) {
            return null;
        }
        List<FileInfo> list = fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getSha256, sha256.toLowerCase())
                .eq(FileInfo::getUploadedBy, uploadedBy)
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
        if (!Objects.equals(SecurityUtils.getUserId(), session.getCreatedBy())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
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
                             FileInfoResponse fileInfo, Boolean directUpload) {
    }

    public record SessionInfo(String uploadId, Integer partSize, Integer partCount,
                              List<Integer> uploadedParts, Boolean directUpload) {
    }
}
