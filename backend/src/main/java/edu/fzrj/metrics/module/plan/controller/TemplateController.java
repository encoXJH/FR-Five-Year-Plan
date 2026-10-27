package edu.fzrj.metrics.module.plan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.plan.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模板接口（API 约定 15.2，全部要求 plan:manage）
 */
@Tag(name = "模板")
@RestController
@RequestMapping("/api/v1/templates")
@PreAuthorize("hasAuthority('plan:manage')")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @Operation(summary = "模板列表（不分页）")
    @GetMapping
    public List<PlanDtos.TemplateItem> list() {
        return templateService.list();
    }

    @Operation(summary = "模板详情（含结构快照 content）")
    @GetMapping("/{templateId}")
    public PlanDtos.TemplateDetail detail(@PathVariable Long templateId) {
        return templateService.detail(templateId);
    }

    @Operation(summary = "修改模板（带 version）")
    @PatchMapping("/{templateId}")
    @AuditLog(action = "UPDATE", resourceType = "template", resourceId = "#templateId")
    public PlanDtos.TemplateDetail patch(@PathVariable Long templateId, @RequestBody JsonNode body) {
        return templateService.patch(templateId, new PatchHelper(body));
    }

    @Operation(summary = "复制模板")
    @PostMapping("/{templateId}/copy")
    @AuditLog(action = "CREATE", resourceType = "template", resourceId = "#templateId")
    public PlanDtos.TemplateDetail copy(@PathVariable Long templateId,
                                        @RequestBody(required = false) PlanDtos.CopyTemplateRequest request) {
        return templateService.copy(templateId, request);
    }

    @Operation(summary = "停用模板（停用后不能用于新建计划，30003）")
    @PostMapping("/{templateId}/disable")
    @AuditLog(action = "UPDATE", resourceType = "template", resourceId = "#templateId")
    public PlanDtos.TemplateItem disable(@PathVariable Long templateId) {
        return templateService.disable(templateId);
    }

    @Operation(summary = "启用模板")
    @PostMapping("/{templateId}/enable")
    @AuditLog(action = "UPDATE", resourceType = "template", resourceId = "#templateId")
    public PlanDtos.TemplateItem enable(@PathVariable Long templateId) {
        return templateService.enable(templateId);
    }
}
