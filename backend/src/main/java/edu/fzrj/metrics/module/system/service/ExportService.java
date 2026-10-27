package edu.fzrj.metrics.module.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.service.SignedUrlService;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.dto.ExportDtos;
import edu.fzrj.metrics.module.system.entity.ExportJob;
import edu.fzrj.metrics.module.system.mapper.ExportJobMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 异步导出任务（API 约定 15.8）：创建 → @Async 生成 xlsx 落盘 → 查询状态拿签名地址。
 * 阶段 2 仅支持 INDICATORS 指标体系导出。
 */
@Service
public class ExportService {

    private static final Set<String> EXPORT_TYPES = Set.of(
            "INDICATORS", "TASKS", "STATS_REPORT", "SETTLEMENT", "AUDIT_LOGS");

    private final ExportJobMapper exportJobMapper;
    private final ExportJobExecutor exportJobExecutor;
    private final PlanService planService;
    private final DataScopeService dataScopeService;
    private final SignedUrlService signedUrlService;
    private final ObjectMapper objectMapper;

    public ExportService(ExportJobMapper exportJobMapper, ExportJobExecutor exportJobExecutor,
                         PlanService planService, DataScopeService dataScopeService,
                         SignedUrlService signedUrlService, ObjectMapper objectMapper) {
        this.exportJobMapper = exportJobMapper;
        this.exportJobExecutor = exportJobExecutor;
        this.planService = planService;
        this.dataScopeService = dataScopeService;
        this.signedUrlService = signedUrlService;
        this.objectMapper = objectMapper;
    }

    public ExportDtos.JobView create(ExportDtos.CreateRequest request) {
        if (!EXPORT_TYPES.contains(request.getType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "导出类型不合法");
        }
        if (!"INDICATORS".equals(request.getType()) && !"STATS_REPORT".equals(request.getType())
                && !"AUDIT_LOGS".equals(request.getType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该导出类型将在后续阶段支持");
        }
        LoginUser user = SecurityUtils.getLoginUser();

        Map<String, Object> jobParams = new LinkedHashMap<>();
        if ("AUDIT_LOGS".equals(request.getType())) {
            // 操作日志导出：仅 audit-log:export（当前仅管理员，API 约定 4.3）；过滤条件原样透传
            if (!user.getPermissions().contains("audit-log:export")) {
                throw new BizException(ErrorCode.FORBIDDEN, "没有操作权限");
            }
            copyParam(request.getParams(), jobParams, "action");
            copyParam(request.getParams(), jobParams, "occurredAtFrom");
            copyParam(request.getParams(), jobParams, "occurredAtTo");
            copyParam(request.getParams(), jobParams, "unitId");
            copyParam(request.getParams(), jobParams, "operatorId");
            copyParam(request.getParams(), jobParams, "resourceType");
            copyParam(request.getParams(), jobParams, "resourceId");
        } else {
            Long planId = parsePlanId(request.getParams().get("planId"));
            planService.requirePlan(planId);
            // 数据范围在创建时固化进任务参数（异步线程没有 SecurityContext）
            Long scopeOwner = null;
            if (!dataScopeService.isAllData(user)) {
                scopeOwner = dataScopeService.isOwnerScope(user) ? user.getUserId() : -1L;
            }
            jobParams.put("planId", String.valueOf(planId));
            jobParams.put("scopeOwner", scopeOwner);
            if ("STATS_REPORT".equals(request.getType())) {
                // 统计报表：管理员 / 校领导 / 校级负责人可导出（PRD P11）；学院负责人无此入口
                if (scopeOwner != null && scopeOwner < 0) {
                    throw new BizException(ErrorCode.FORBIDDEN, "没有操作权限");
                }
                jobParams.put("year", parseYear(request.getParams().get("year")));
            }
        }

        ExportJob job = new ExportJob();
        job.setType(request.getType());
        try {
            job.setParams(objectMapper.writeValueAsString(jobParams));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "导出参数序列化失败");
        }
        job.setStatus("PENDING");
        job.setCreatedBy(user.getUserId());
        exportJobMapper.insert(job);
        exportJobExecutor.run(job.getId());
        // 重查以拿到 DB 默认的 createdAt（异步线程可能已开始执行）
        ExportJob current = exportJobMapper.selectById(job.getId());
        return toView(current, null, null);
    }

    private void copyParam(Map<String, Object> from, Map<String, Object> to, String key) {
        Object value = from.get(key);
        if (value != null && !"null".equals(String.valueOf(value))) {
            to.put(key, value);
        }
    }

    public ExportDtos.JobView detail(Long exportId) {
        ExportJob job = exportJobMapper.selectById(exportId);
        if (job == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导出任务不存在");
        }
        if (!job.getCreatedBy().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        String downloadUrl = null;
        java.time.LocalDateTime expiresAt = null;
        if ("SUCCEEDED".equals(job.getStatus()) && job.getFileId() != null) {
            SignedUrlService.SignedUrl signed = signedUrlService.sign(job.getFileId());
            downloadUrl = signed.url();
            expiresAt = signed.expiresAt();
        }
        return toView(job, downloadUrl, expiresAt);
    }

    private Long parsePlanId(Object raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "导出参数缺少 planId");
        }
        try {
            return raw instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "planId 格式不正确");
        }
    }

    private Integer parseYear(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return raw instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "year 格式不正确");
        }
    }

    private ExportDtos.JobView toView(ExportJob job, String downloadUrl, java.time.LocalDateTime expiresAt) {
        return new ExportDtos.JobView(job.getId(), job.getType(), job.getStatus(), job.getErrorMessage(),
                downloadUrl, expiresAt, job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt());
    }
}
