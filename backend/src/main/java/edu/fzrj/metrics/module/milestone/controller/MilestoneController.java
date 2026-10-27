package edu.fzrj.metrics.module.milestone.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.milestone.dto.MilestoneDtos;
import edu.fzrj.metrics.module.milestone.service.MilestoneService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 里程碑接口（API 约定 15.7）
 */
@Tag(name = "里程碑")
@RestController
@RequestMapping("/api/v1/milestones")
public class MilestoneController {

    private final MilestoneService milestoneService;

    public MilestoneController(MilestoneService milestoneService) {
        this.milestoneService = milestoneService;
    }

    @Operation(summary = "里程碑列表（indicatorId 与 taskId 二选一）", description = "已登录，按数据范围，越权 20005")
    @GetMapping
    public List<MilestoneDtos.View> list(@RequestParam(required = false) Long indicatorId,
                                         @RequestParam(required = false) Long taskId) {
        return milestoneService.list(indicatorId, taskId);
    }

    @Operation(summary = "新建里程碑",
            description = "权限：indicator:edit（指标级）/ submission:submit（任务级，本单元）；"
                    + "indicatorId 与 taskId 二选一；初始状态仅 NOT_STARTED / IN_PROGRESS")
    @PostMapping
    @PreAuthorize("hasAnyAuthority('indicator:edit','submission:submit')")
    @AuditLog(action = "CREATE", resourceType = "milestone", resourceId = "#result.id")
    public MilestoneDtos.View create(@Valid @RequestBody MilestoneDtos.CreateRequest request) {
        return milestoneService.create(request);
    }

    @Operation(summary = "修改里程碑",
            description = "权限：indicator:edit / submission:submit（按里程碑归属做数据范围校验）；需带 version；"
                    + "置为 COMPLETED 时自动记录完成时间")
    @PatchMapping("/{milestoneId}")
    @PreAuthorize("hasAnyAuthority('indicator:edit','submission:submit')")
    @AuditLog(action = "UPDATE", resourceType = "milestone", resourceId = "#milestoneId")
    public MilestoneDtos.View patch(@PathVariable Long milestoneId, @RequestBody JsonNode body) {
        return milestoneService.patch(milestoneId, new PatchHelper(body));
    }

    @Operation(summary = "删除里程碑（逻辑删除）",
            description = "权限：indicator:edit / submission:submit（按数据范围）；需带 version（查询参数）")
    @DeleteMapping("/{milestoneId}")
    @PreAuthorize("hasAnyAuthority('indicator:edit','submission:submit')")
    @AuditLog(action = "DELETE", resourceType = "milestone", resourceId = "#milestoneId")
    public void delete(@PathVariable Long milestoneId, @RequestParam(required = false) Integer version) {
        milestoneService.delete(milestoneId, version);
    }
}
