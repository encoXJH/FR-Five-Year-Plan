package edu.fzrj.metrics.module.indicator.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.indicator.dto.DirectionDtos;
import edu.fzrj.metrics.module.indicator.service.DirectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主要S 级任务接口（API 约定 15.3）
 */
@Tag(name = "S 级任务")
@RestController
public class DirectionController {

    private final DirectionService directionService;

    public DirectionController(DirectionService directionService) {
        this.directionService = directionService;
    }

    @Operation(summary = "S 级任务列表（不分页）", description = "权限：system:manage")
    @GetMapping("/api/v1/plans/{planId}/directions")
    @PreAuthorize("hasAuthority('system:manage')")
    public List<DirectionDtos.Item> list(@PathVariable Long planId) {
        return directionService.list(planId);
    }

    @Operation(summary = "新增S 级任务", description = "权限：system:manage")
    @PostMapping("/api/v1/plans/{planId}/directions")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "CREATE", resourceType = "direction", resourceId = "#planId")
    public DirectionDtos.Item create(@PathVariable Long planId,
                                     @Valid @RequestBody DirectionDtos.CreateRequest request) {
        return directionService.create(planId, request);
    }

    @Operation(summary = "修改S 级任务（带 version）", description = "权限：system:manage")
    @PatchMapping("/api/v1/directions/{directionId}")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "UPDATE", resourceType = "direction", resourceId = "#directionId")
    public DirectionDtos.Item patch(@PathVariable Long directionId, @RequestBody JsonNode body) {
        return directionService.patch(directionId, new PatchHelper(body));
    }

    @Operation(summary = "删除S 级任务（其下有指标时返回 40008）", description = "权限：system:manage")
    @DeleteMapping("/api/v1/directions/{directionId}")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "DELETE", resourceType = "direction", resourceId = "#directionId")
    public void delete(@PathVariable Long directionId) {
        directionService.delete(directionId);
    }
}
