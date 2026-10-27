package edu.fzrj.metrics.module.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.service.MajorGroupService;
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
 * 专业群（API 约定 15.10、PRD FR-M12-02）：列表已登录即可（建设方向选择器），写操作 system:manage
 */
@Tag(name = "专业群")
@RestController
@RequestMapping("/api/v1/major-groups")
public class MajorGroupController {

    private final MajorGroupService majorGroupService;

    public MajorGroupController(MajorGroupService majorGroupService) {
        this.majorGroupService = majorGroupService;
    }

    @Operation(summary = "专业群列表（已登录即可；可按 unitId 过滤）")
    @GetMapping
    public List<SystemDtos.MajorGroupView> list(@RequestParam(required = false) Long unitId) {
        return majorGroupService.list(unitId);
    }

    @Operation(summary = "新增专业群",
            description = "权限：system:manage；unitId 必填且必须存在；ownerId 可选")
    @PostMapping
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "CREATE", resourceType = "major_group", resourceId = "#result.id")
    public SystemDtos.MajorGroupView create(@Valid @RequestBody SystemDtos.MajorGroupCreateRequest request) {
        return majorGroupService.create(request);
    }

    @Operation(summary = "修改专业群",
            description = "权限：system:manage；可改 name / unitId / ownerId（可清空）/ isEnabled；必须带 version")
    @PatchMapping("/{majorGroupId}")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "UPDATE", resourceType = "major_group", resourceId = "#majorGroupId")
    public SystemDtos.MajorGroupView patch(@PathVariable Long majorGroupId, @RequestBody JsonNode body) {
        return majorGroupService.patch(majorGroupId, body);
    }
}
