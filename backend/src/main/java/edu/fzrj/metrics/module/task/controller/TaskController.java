package edu.fzrj.metrics.module.task.controller;

import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
import edu.fzrj.metrics.module.task.service.TaskService;
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

/**
 * B 任务接口（API 约定 15.4）
 */
@Tag(name = "B 任务")
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "B 任务分页列表（过滤 unitId / indicatorId / year / status / yearStatus / isHistorical）",
            description = "已登录，按数据范围：管理员 / 校领导全部；校级负责人看本人负责指标；学院负责人看本单元")
    @GetMapping
    public PageResult<TaskDtos.TaskListItem> page(@ModelAttribute TaskDtos.TaskQuery query) {
        return taskService.page(query);
    }

    @Operation(summary = "B 任务详情（各年度目标、完成值、状态）", description = "按数据范围，越权 20005")
    @GetMapping("/{taskId}")
    public TaskDtos.TaskDetail detail(@PathVariable Long taskId) {
        return taskService.detail(taskId);
    }

    @Operation(summary = "学院确认接收（DISTRIBUTED → IN_PROGRESS；重复接收幂等）",
            description = "权限：task:accept，仅本单元；需带 version；必须带 Idempotency-Key")
    @PostMapping("/{taskId}/accept")
    @PreAuthorize("hasAuthority('task:accept')")
    @Idempotent
    @AuditLog(action = "ACCEPT", resourceType = "task", resourceId = "#taskId")
    public TaskDtos.TaskDetail accept(@PathVariable Long taskId,
                                      @Valid @RequestBody PlanDtos.VersionRequest request) {
        return taskService.accept(taskId, request.getVersion());
    }
}
