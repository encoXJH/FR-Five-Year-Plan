package edu.fzrj.metrics.module.task.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.task.dto.TaskCompletionLinkDtos;
import edu.fzrj.metrics.module.task.service.TaskCompletionLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "任务完成进度关联")
@RestController
@RequestMapping("/api/v1/task-completion-links")
@PreAuthorize("hasAuthority('system:manage')")
public class TaskCompletionLinkController {

    private final TaskCompletionLinkService completionLinkService;

    public TaskCompletionLinkController(TaskCompletionLinkService completionLinkService) {
        this.completionLinkService = completionLinkService;
    }

    @Operation(summary = "创建任务完成进度关联",
            description = "权限：system:manage；必须带 Idempotency-Key；允许跨计划，双方必须为非历史任务且统计口径一致；"
                    + "一对任务按 ID 规范化存储，建联后立即双向重算各年度审核完成值和累计进度")
    @PostMapping
    @Idempotent
    @AuditLog(action = "CREATE", resourceType = "task_completion_link", resourceId = "#result.id")
    public TaskCompletionLinkDtos.View create(@Valid @RequestBody TaskCompletionLinkDtos.CreateRequest request) {
        return completionLinkService.create(request);
    }

    @Operation(summary = "任务完成进度关联列表",
            description = "权限：system:manage；可通过 taskId 过滤任一侧包含该任务的直接关联")
    @GetMapping
    public List<TaskCompletionLinkDtos.View> list(@RequestParam(required = false) Long taskId) {
        return completionLinkService.list(taskId);
    }
}
