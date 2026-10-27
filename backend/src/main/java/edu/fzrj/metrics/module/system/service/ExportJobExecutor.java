package edu.fzrj.metrics.module.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.module.audit.service.AuditLogService;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.service.StorageService;
import edu.fzrj.metrics.module.indicator.service.IndicatorExcelService;
import edu.fzrj.metrics.module.stats.service.StatsExcelService;
import edu.fzrj.metrics.module.system.entity.ExportJob;
import edu.fzrj.metrics.module.system.mapper.ExportJobMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 导出任务执行器（@Async）：生成 xlsx → StorageService 落盘 → 建 file_info → 回写任务状态。
 * 注意：异步线程没有 SecurityContext，数据范围由任务参数中的 scopeOwner 决定。
 */
@Slf4j
@Component
public class ExportJobExecutor {

    private final ExportJobMapper exportJobMapper;
    private final IndicatorExcelService indicatorExcelService;
    private final StatsExcelService statsExcelService;
    private final AuditLogService auditLogService;
    private final StorageService storageService;
    private final FileInfoMapper fileInfoMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.file.storage-type:LOCAL}")
    private String storageType;

    public ExportJobExecutor(ExportJobMapper exportJobMapper, IndicatorExcelService indicatorExcelService,
                             StatsExcelService statsExcelService, AuditLogService auditLogService,
                             StorageService storageService, FileInfoMapper fileInfoMapper,
                             ObjectMapper objectMapper) {
        this.exportJobMapper = exportJobMapper;
        this.indicatorExcelService = indicatorExcelService;
        this.statsExcelService = statsExcelService;
        this.auditLogService = auditLogService;
        this.storageService = storageService;
        this.fileInfoMapper = fileInfoMapper;
        this.objectMapper = objectMapper;
    }

    @Async
    public void run(Long jobId) {
        ExportJob job = exportJobMapper.selectById(jobId);
        if (job == null || !"PENDING".equals(job.getStatus())) {
            return;
        }
        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());
        exportJobMapper.updateById(job);
        try {
            Map<?, ?> params = objectMapper.readValue(job.getParams(), Map.class);
            byte[] bytes;
            String fileLabel;
            if ("AUDIT_LOGS".equals(job.getType())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> filter = (Map<String, Object>) params;
                bytes = auditLogService.buildExport(filter);
                fileLabel = "操作日志";
            } else {
                Long planId = Long.valueOf(String.valueOf(params.get("planId")));
                Object scopeOwnerRaw = params.get("scopeOwner");
                Long scopeOwner = scopeOwnerRaw == null || "null".equals(String.valueOf(scopeOwnerRaw))
                        ? null : Long.valueOf(String.valueOf(scopeOwnerRaw));
                if ("STATS_REPORT".equals(job.getType())) {
                    Object yearRaw = params.get("year");
                    Integer year = yearRaw == null || "null".equals(String.valueOf(yearRaw))
                            ? null : Integer.valueOf(String.valueOf(yearRaw));
                    bytes = statsExcelService.buildStatsReport(planId, year, scopeOwner);
                    fileLabel = "统计报表-" + planId + "-" + (year == null ? "current" : year);
                } else {
                    bytes = indicatorExcelService.buildIndicatorsExport(planId, scopeOwner);
                    fileLabel = "指标体系-" + planId;
                }
            }
            FileInfo fileInfo = storeFile(bytes, fileLabel, job.getCreatedBy());
            job.setStatus("SUCCEEDED");
            job.setFileId(fileInfo.getId());
            job.setFinishedAt(LocalDateTime.now());
            exportJobMapper.updateById(job);
        } catch (Exception e) {
            log.error("导出任务执行失败 jobId={}", jobId, e);
            job.setStatus("FAILED");
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.setErrorMessage(message.length() > 2000 ? message.substring(0, 2000) : message);
            job.setFinishedAt(LocalDateTime.now());
            exportJobMapper.updateById(job);
        }
    }

    private FileInfo storeFile(byte[] bytes, String fileLabel, Long operatorId) throws Exception {
        String storageKey = "exports/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + UUID.randomUUID().toString().replace("-", "") + ".xlsx";
        storageService.save(new ByteArrayInputStream(bytes), storageKey);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sha256 = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sha256.append(Character.forDigit((b >> 4) & 0xF, 16));
            sha256.append(Character.forDigit(b & 0xF, 16));
        }
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileLabel + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx");
        fileInfo.setExt("xlsx");
        fileInfo.setSize((long) bytes.length);
        fileInfo.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        fileInfo.setCategory("DOCUMENT");
        // V1 chk_file_biz_type 无 EXPORT_FILE 取值，阶段 2 复用 IMPORT_FILE（见开发决策记录）
        fileInfo.setBizType("IMPORT_FILE");
        fileInfo.setStatus("BOUND");
        fileInfo.setStorageType(storageType);
        fileInfo.setStorageKey(storageKey);
        fileInfo.setSha256(sha256.toString());
        fileInfo.setUploadedBy(operatorId);
        fileInfo.setUploadedAt(LocalDateTime.now());
        fileInfo.setBoundAt(LocalDateTime.now());
        fileInfo.setCreatedBy(operatorId);
        fileInfo.setUpdatedBy(operatorId);
        fileInfoMapper.insert(fileInfo);
        return fileInfo;
    }
}
