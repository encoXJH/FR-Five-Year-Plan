package edu.fzrj.metrics.module.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 执行单元（API 约定 15.10；列表已登录即可，新增 / 修改 / 停用属 M12 系统管理）
 */
@Tag(name = "执行单元")
@RestController
@RequestMapping("/api/v1/org-units")
public class OrgUnitController {

    private final OrgUnitAdminService orgUnitAdminService;

    public OrgUnitController(OrgUnitAdminService orgUnitAdminService) {
        this.orgUnitAdminService = orgUnitAdminService;
    }

    @Operation(summary = "执行单元列表（已登录即可；分解矩阵、学院工作台等场景使用）")
    @GetMapping
    public List<SystemDtos.OrgUnitView> list() {
        return orgUnitAdminService.listAll();
    }

    @Operation(summary = "新增执行单元",
            description = "权限：system:manage；编码只允许大写字母与数字且全平台唯一；类型见 OrgUnitType 枚举")
    @PostMapping
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "CREATE", resourceType = "org_unit", resourceId = "#result.id")
    public SystemDtos.OrgUnitView create(@Valid @RequestBody SystemDtos.OrgUnitCreateRequest request) {
        return orgUnitAdminService.create(request);
    }

    @Operation(summary = "修改或停用执行单元",
            description = "权限：system:manage；可改 name / sort / isEnabled / isBAssignable；编码与类型创建后不可修改；"
                    + "停用前校验非历史在途 B 任务（已下发 / 执行中），有则拒绝；必须带 version")
    @PatchMapping("/{unitId}")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "UPDATE", resourceType = "org_unit", resourceId = "#unitId")
    public SystemDtos.OrgUnitView patch(@PathVariable Long unitId, @RequestBody JsonNode body) {
        return orgUnitAdminService.patch(unitId, body);
    }
}
