package edu.fzrj.metrics.module.audit.controller;

import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.module.audit.dto.AuditLogDtos;
import edu.fzrj.metrics.module.audit.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询（API 约定 15.9 / 16.6；日志只追加，无修改删除接口）
 */
@Tag(name = "审计日志")
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Operation(summary = "查询审计日志",
            description = "权限：audit-log:view；过滤：occurredAtFrom/occurredAtTo（ISO 8601）、unitId、"
                    + "action（逗号多值）、operatorId、resourceType、resourceId；默认按 occurredAt 倒序；"
                    + "导出走 POST /exports（type=AUDIT_LOGS，权限 audit-log:export）")
    @GetMapping
    @PreAuthorize("hasAuthority('audit-log:view')")
    public PageResult<AuditLogDtos.LogItem> page(@ModelAttribute AuditLogDtos.AuditQuery query) {
        return auditLogService.page(query);
    }
}
