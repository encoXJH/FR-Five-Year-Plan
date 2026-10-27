package edu.fzrj.metrics.module.plan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.plan.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划接口（API 约定 15.2）
 */
@Tag(name = "计划")
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;
    private final edu.fzrj.metrics.module.plan.service.PlanYearService planYearService;

    public PlanController(PlanService planService,
                          edu.fzrj.metrics.module.plan.service.PlanYearService planYearService) {
        this.planService = planService;
        this.planYearService = planYearService;
    }

    @Operation(summary = "计划列表（首页卡片，不分页；completionRate 阶段 2 恒为 null）")
    @GetMapping
    public List<PlanDtos.ListItem> list(@RequestParam(required = false) String status) {
        return planService.list(status);
    }

    @Operation(summary = "新建计划（source：BLANK / TEMPLATE / COPY）", description = "权限：plan:manage")
    @PostMapping
    @PreAuthorize("hasAuthority('plan:manage')")
    @AuditLog(action = "CREATE", resourceType = "plan", resourceId = "#result.id")
    public PlanDtos.Detail create(@Valid @RequestBody PlanDtos.CreateRequest request) {
        return planService.create(request);
    }

    @Operation(summary = "计划详情")
    @GetMapping("/{planId}")
    public PlanDtos.Detail detail(@PathVariable Long planId) {
        return planService.detail(planId);
    }

    @Operation(summary = "修改计划属性（带 version；起止年份仅草稿可改）", description = "权限：plan:manage")
    @PatchMapping("/{planId}")
    @PreAuthorize("hasAuthority('plan:manage')")
    @AuditLog(action = "UPDATE", resourceType = "plan", resourceId = "#planId")
    public PlanDtos.Detail patch(@PathVariable Long planId, @RequestBody JsonNode body) {
        return planService.patch(planId, new PatchHelper(body));
    }

    @Operation(summary = "启动执行（DRAFT → ACTIVE，生成各年度计划年度行）", description = "权限：plan:manage；需带 version")
    @PostMapping("/{planId}/activate")
    @PreAuthorize("hasAuthority('plan:manage')")
    @AuditLog(action = "UPDATE", resourceType = "plan", resourceId = "#planId")
    public PlanDtos.Detail activate(@PathVariable Long planId,
                                    @Valid @RequestBody PlanDtos.VersionRequest request) {
        return planService.activate(planId, request.getVersion());
    }

    @Operation(summary = "归档（归档后只读，写操作返回 30002）", description = "权限：plan:manage；需带 version")
    @PostMapping("/{planId}/archive")
    @PreAuthorize("hasAuthority('plan:manage')")
    @AuditLog(action = "UPDATE", resourceType = "plan", resourceId = "#planId")
    public PlanDtos.Detail archive(@PathVariable Long planId,
                                   @Valid @RequestBody PlanDtos.VersionRequest request) {
        return planService.archive(planId, request.getVersion());
    }

    @Operation(summary = "激活年度（激活后才能填报；该年已下发任务的年度状态置为进行中；重复激活幂等）",
            description = "权限：settlement:manage；需带 version（计划版本号）；必须带 Idempotency-Key")
    @PostMapping("/{planId}/years/{year}/activate")
    @PreAuthorize("hasAuthority('settlement:manage')")
    @edu.fzrj.metrics.common.idempotent.Idempotent
    @AuditLog(action = "UPDATE", resourceType = "plan", resourceId = "#planId")
    public PlanDtos.Detail activateYear(@PathVariable Long planId, @PathVariable Integer year,
                                        @Valid @RequestBody PlanDtos.VersionRequest request) {
        return planYearService.activateYear(planId, year, request.getVersion());
    }

    @Operation(summary = "另存为模板（结构快照，不含执行数据）", description = "权限：plan:manage")
    @PostMapping("/{planId}/save-as-template")
    @PreAuthorize("hasAuthority('plan:manage')")
    @AuditLog(action = "CREATE", resourceType = "template", resourceId = "#planId")
    public PlanDtos.TemplateDetail saveAsTemplate(@PathVariable Long planId,
                                                  @Valid @RequestBody PlanDtos.SaveAsTemplateRequest request) {
        return planService.saveAsTemplate(planId, request);
    }
}
