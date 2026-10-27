package edu.fzrj.metrics.module.system.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.system.dto.ExportDtos;
import edu.fzrj.metrics.module.system.service.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步导出接口（API 约定 15.8；所有 Excel 导出统一走异步任务）
 */
@Tag(name = "导出")
@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @Operation(summary = "创建异步导出任务 { type, params }，返回 exportId",
            description = "已登录；INDICATORS（params.planId）；STATS_REPORT（params.planId + 可选 year，管理员/校领导/校级负责人）；"
                    + "AUDIT_LOGS（params 同 GET /audit-logs 过滤条件，无需 planId，权限 audit-log:export）；必须带 Idempotency-Key")
    @PostMapping
    @Idempotent
    @AuditLog(action = "EXPORT", resourceType = "export", resourceId = "#request.params['planId']")
    public ExportDtos.CreatedView create(@Valid @RequestBody ExportDtos.CreateRequest request) {
        return new ExportDtos.CreatedView(exportService.create(request).id());
    }

    @Operation(summary = "查询导出任务状态；成功时返回签名下载地址（仅任务创建人可查）")
    @GetMapping("/{exportId}")
    public ExportDtos.JobView detail(@PathVariable Long exportId) {
        return exportService.detail(exportId);
    }
}
