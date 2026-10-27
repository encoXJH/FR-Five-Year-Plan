package edu.fzrj.metrics.module.task.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
import edu.fzrj.metrics.module.task.service.AllocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分解矩阵接口（API 约定 15.4 / 16.3）
 */
@Tag(name = "分解矩阵")
@RestController
public class AllocationController {

    private final AllocationService allocationService;

    public AllocationController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    @Operation(summary = "获取分解矩阵（A+ 或不可下分 A：执行单元 × 年度 + 合计校验）",
            description = "权限：allocation:edit；按数据范围（校级负责人仅本人负责的指标）")
    @GetMapping("/api/v1/indicators/{indicatorId}/allocation")
    @PreAuthorize("hasAuthority('allocation:edit')")
    public TaskDtos.AllocationView get(@PathVariable Long indicatorId) {
        return allocationService.getAllocation(indicatorId);
    }

    @Operation(summary = "保存分解矩阵（整体替换；允许合计不一致存草稿，下发时强制校验 50001；已下发指标 50003）",
            description = "权限：allocation:edit；需带 version；不可下分 A 只允许一个承接单元")
    @PutMapping("/api/v1/indicators/{indicatorId}/allocation")
    @PreAuthorize("hasAuthority('allocation:edit')")
    @AuditLog(action = "UPDATE", resourceType = "indicator", resourceId = "#indicatorId")
    public TaskDtos.AllocationView put(@PathVariable Long indicatorId,
                                       @Valid @RequestBody TaskDtos.AllocationPutRequest request) {
        return allocationService.putAllocation(indicatorId, request);
    }
}
