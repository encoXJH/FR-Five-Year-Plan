package edu.fzrj.metrics.module.system.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.module.system.dto.RolePermissionDtos;
import edu.fzrj.metrics.module.system.service.RolePermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 四类固定角色的功能权限矩阵。 */
@Tag(name = "角色权限")
@RestController
@RequestMapping("/api/v1/role-permissions")
public class RolePermissionController {

    private final RolePermissionService rolePermissionService;

    public RolePermissionController(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    @Operation(summary = "角色权限矩阵",
            description = "权限：system:manage（仅 ADMIN）；返回四类固定角色、权限目录、当前授权和按角色版本号；"
                    + "configurableRoles 为可开关组合，lockedRoles 为必须保留组合，数据范围不在此配置")
    @GetMapping
    @PreAuthorize("hasAuthority('system:manage')")
    public RolePermissionDtos.MatrixView matrix() {
        return rolePermissionService.matrix();
    }

    @Operation(summary = "设置角色功能权限（敏感操作）",
            description = "权限：system:manage（仅 ADMIN）；整体替换指定角色的功能权限，必须带 reason 和 version；"
                    + "核心权限、固定审批资格及角色编码不可修改，变更前后权限写入 PERMISSION_CHANGE 审计")
    @PutMapping("/{role}")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "PERMISSION_CHANGE", resourceType = "role_permission", resourceId = "#role",
            reason = "#request.reason", before = "#result.auditBefore", after = "#result.auditAfter")
    public RolePermissionDtos.RoleItem update(@PathVariable String role,
                                               @Valid @RequestBody RolePermissionDtos.UpdateRequest request) {
        return rolePermissionService.update(role, request);
    }
}
