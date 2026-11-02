package edu.fzrj.metrics.module.task.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.task.dto.TaskDependencyDtos;
import edu.fzrj.metrics.module.task.service.TaskDependencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "任务依赖")
@RestController
public class TaskDependencyController {

    private final TaskDependencyService dependencyService;

    public TaskDependencyController(TaskDependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    @Operation(summary = "创建 B 任务 FS 依赖",
            description = "权限：allocation:edit；必须带 Idempotency-Key；校验数据范围、同计划、非历史、非归档、非自依赖、非重复及无环；错误复用 10001/20005/30002/50006")
    @PostMapping("/api/v1/plans/{planId}/task-dependencies")
    @PreAuthorize("hasAuthority('allocation:edit')")
    @Idempotent
    @AuditLog(action = "CREATE", resourceType = "task_dependency", resourceId = "#result.id")
    public TaskDependencyDtos.View create(@PathVariable Long planId,
                                          @Valid @RequestBody TaskDependencyDtos.CreateRequest request) {
        return dependencyService.create(planId, request);
    }

    @Operation(summary = "删除 B 任务依赖（逻辑删除）",
            description = "权限：allocation:edit；按任务数据范围校验；必须携带 version，冲突返回 10005；历史任务及归档计划只读")
    @DeleteMapping("/api/v1/task-dependencies/{id}")
    @PreAuthorize("hasAuthority('allocation:edit')")
    @AuditLog(action = "DELETE", resourceType = "task_dependency", resourceId = "#id")
    public void delete(@PathVariable Long id, @RequestParam(required = false) Integer version) {
        dependencyService.delete(id, version);
    }
}
