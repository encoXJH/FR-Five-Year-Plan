package edu.fzrj.metrics.module.submission.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.service.SubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 填报接口（API 约定 15.5）
 */
@Tag(name = "填报")
@RestController
@RequestMapping("/api/v1")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @Operation(summary = "某 B 任务的填报记录", description = "已登录，按数据范围，越权 20005")
    @GetMapping("/tasks/{taskId}/submissions")
    public PageResult<SubmissionDtos.ListItem> page(@PathVariable Long taskId,
                                                    @ModelAttribute SubmissionDtos.SubmissionQuery query) {
        return submissionService.page(taskId, query);
    }

    @Operation(summary = "新建填报草稿", description = "权限：submission:submit（本单元；管理员代填需 proxyReason）")
    @PostMapping("/tasks/{taskId}/submissions")
    @PreAuthorize("hasAuthority('submission:submit')")
    @AuditLog(action = "CREATE", resourceType = "submission", resourceId = "#result.id")
    public SubmissionDtos.Detail create(@PathVariable Long taskId,
                                        @Valid @RequestBody SubmissionDtos.CreateRequest request) {
        return submissionService.create(taskId, request);
    }

    @Operation(summary = "填报详情（含材料、审核记录、历史快照）", description = "按数据范围，越权 20005")
    @GetMapping("/submissions/{submissionId}")
    public SubmissionDtos.Detail detail(@PathVariable Long submissionId) {
        return submissionService.detail(submissionId);
    }

    @Operation(summary = "修改草稿或被驳回 / 已撤回的填报", description = "权限：submission:submit；需带 version")
    @PatchMapping("/submissions/{submissionId}")
    @PreAuthorize("hasAuthority('submission:submit')")
    @AuditLog(action = "UPDATE", resourceType = "submission", resourceId = "#submissionId")
    public SubmissionDtos.Detail patch(@PathVariable Long submissionId, @RequestBody JsonNode body) {
        return submissionService.patch(submissionId, new PatchHelper(body));
    }

    @Operation(summary = "删除草稿", description = "权限：submission:submit；仅 DRAFT 可删")
    @DeleteMapping("/submissions/{submissionId}")
    @PreAuthorize("hasAuthority('submission:submit')")
    @AuditLog(action = "DELETE", resourceType = "submission", resourceId = "#submissionId")
    public void delete(@PathVariable Long submissionId) {
        submissionService.delete(submissionId);
    }

    @Operation(summary = "提交审核（DRAFT / REJECTED / WITHDRAWN → PENDING_REVIEW）",
            description = "权限：submission:submit；需带 version；必须带 Idempotency-Key；"
                    + "材料不足 60002 / 年度未激活 50004 / 年度已锁定 60003 / 口径不符 60006")
    @PostMapping("/submissions/{submissionId}/submit")
    @PreAuthorize("hasAuthority('submission:submit')")
    @Idempotent
    @AuditLog(action = "SUBMIT", resourceType = "submission", resourceId = "#submissionId")
    public SubmissionDtos.Detail submit(@PathVariable Long submissionId,
                                        @Valid @RequestBody PlanDtos.VersionRequest request) {
        return submissionService.submit(submissionId, request.getVersion());
    }

    @Operation(summary = "撤回（审核前才可以，PENDING_REVIEW → WITHDRAWN）",
            description = "权限：submission:submit；需带 version；必须带 Idempotency-Key")
    @PostMapping("/submissions/{submissionId}/withdraw")
    @PreAuthorize("hasAuthority('submission:submit')")
    @Idempotent
    @AuditLog(action = "WITHDRAW", resourceType = "submission", resourceId = "#submissionId")
    public SubmissionDtos.Detail withdraw(@PathVariable Long submissionId,
                                          @Valid @RequestBody PlanDtos.VersionRequest request) {
        return submissionService.withdraw(submissionId, request.getVersion());
    }
}
