package edu.fzrj.metrics.module.adjustment.controller;

import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.adjustment.dto.AdjustmentDtos;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentDecisionService;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentService;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 指标调整接口（API 约定 15.6）
 */
@Tag(name = "指标调整")
@RestController
@RequestMapping("/api/v1")
public class AdjustmentController {

    private final AdjustmentService adjustmentService;
    private final AdjustmentDecisionService decisionService;

    public AdjustmentController(AdjustmentService adjustmentService,
                                AdjustmentDecisionService decisionService) {
        this.adjustmentService = adjustmentService;
        this.decisionService = decisionService;
    }

    @Operation(summary = "试算调整前后的数据和守恒校验结果（不保存）",
            description = "权限：adjustment:apply；返回 valid / errors / snapshot / toTaskExists")
    @PostMapping("/adjustments/preview")
    @PreAuthorize("hasAuthority('adjustment:apply')")
    public AdjustmentDtos.PreviewResponse preview(@Valid @RequestBody AdjustmentDtos.CreateRequest request) {
        return adjustmentService.preview(request);
    }

    @Operation(summary = "发起调整申请",
            description = "权限：adjustment:apply（TARGET_CHANGE 仅管理员）；必须带 Idempotency-Key；"
                    + "申请时冻结额度并写调整前快照；70001 数量超额 / 70002 违反守恒 / 70003 年份不合法 / "
                    + "70004 额度已冻结 / 70006 口径不支持")
    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('adjustment:apply')")
    @Idempotent
    @AuditLog(action = "ADJUST_APPLY", resourceType = "adjustment", resourceId = "#result.id", reason = "#request.reason")
    public AdjustmentDtos.Detail create(@Valid @RequestBody AdjustmentDtos.CreateRequest request) {
        return adjustmentService.create(request);
    }

    @Operation(summary = "调整申请列表",
            description = "scope=MINE 我发起的（默认）/ TODO 待我处理 / ALL 全部（按数据范围）；已登录")
    @GetMapping("/adjustments")
    public PageResult<AdjustmentDtos.ListItem> page(@ModelAttribute AdjustmentDtos.AdjustmentQuery query) {
        return adjustmentService.page(query);
    }

    @Operation(summary = "调整申请详情（含审批环节、调整前后快照、附件）",
            description = "按数据范围（申请人 / 转出与接收单元 / 指标负责人 / 全校角色），越权 20005")
    @GetMapping("/adjustments/{adjustmentId}")
    public AdjustmentDtos.Detail detail(@PathVariable Long adjustmentId) {
        return adjustmentService.detail(adjustmentId);
    }

    @Operation(summary = "当前环节的处理（服务端自动判断环节与处理人）",
            description = "权限：adjustment:decide；必须带 Idempotency-Key 与 version；REJECT 必须填写 comment（60004）；"
                    + "非当前环节处理人 70005；最后环节通过后事务内生效")
    @PostMapping("/adjustments/{adjustmentId}/decision")
    @PreAuthorize("hasAuthority('adjustment:decide')")
    @Idempotent
    @AuditLog(action = "ADJUST_DECIDE", resourceType = "adjustment",
            resourceId = "#adjustmentId", reason = "#request.comment")
    public AdjustmentDtos.Detail decide(@PathVariable Long adjustmentId,
                                        @Valid @RequestBody AdjustmentDtos.DecisionRequest request) {
        return decisionService.decide(adjustmentId, request);
    }

    @Operation(summary = "申请人撤回（生效前才可以）",
            description = "仅申请人；必须带 Idempotency-Key 与 version；撤回释放冻结额度")
    @PostMapping("/adjustments/{adjustmentId}/withdraw")
    @PreAuthorize("hasAuthority('adjustment:apply')")
    @Idempotent
    @AuditLog(action = "WITHDRAW", resourceType = "adjustment", resourceId = "#adjustmentId")
    public AdjustmentDtos.Detail withdraw(@PathVariable Long adjustmentId,
                                          @Valid @RequestBody PlanDtos.VersionRequest request) {
        return adjustmentService.withdraw(adjustmentId, request.getVersion());
    }

    @Operation(summary = "某 B 任务的调整历史时间线（含作为转出方 / 接收方 / 由调整新建的记录）",
            description = "按任务数据范围，越权 20005")
    @GetMapping("/tasks/{taskId}/adjustments")
    public List<AdjustmentDtos.Detail> timelineOfTask(@PathVariable Long taskId) {
        return adjustmentService.timelineOfTask(taskId);
    }
}
