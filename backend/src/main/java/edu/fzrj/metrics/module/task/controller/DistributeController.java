package edu.fzrj.metrics.module.task.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
import edu.fzrj.metrics.module.task.service.DistributeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务下发接口（API 约定 15.4）
 */
@Tag(name = "任务下发")
@RestController
public class DistributeController {

    private final DistributeService distributeService;

    public DistributeController(DistributeService distributeService) {
        this.distributeService = distributeService;
    }

    @Operation(summary = "直接下发 B 任务（indicatorIds 为空 = 全部可下发指标；下发前校验完整性、40002、40003、50001、50002；已下发的跳过）",
            description = "权限：task:distribute；需带 version；必须带 Idempotency-Key")
    @PostMapping("/api/v1/plans/{planId}/distribute")
    @PreAuthorize("hasAuthority('task:distribute')")
    @Idempotent
    @AuditLog(action = "DISTRIBUTE", resourceType = "plan", resourceId = "#planId",
            before = "auditSnapshot(#planId)", after = "auditSnapshot(#planId)")
    public TaskDtos.DistributeResponse distribute(@PathVariable Long planId,
                                                  @Valid @RequestBody TaskDtos.DistributeRequest request) {
        return distributeService.distribute(planId, request);
    }

    public Object auditSnapshot(Long planId) {
        return distributeService.auditSnapshot(planId);
    }
}
