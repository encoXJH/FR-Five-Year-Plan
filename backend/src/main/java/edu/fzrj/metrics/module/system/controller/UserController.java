package edu.fzrj.metrics.module.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.service.UserAdminService;
import edu.fzrj.metrics.module.system.service.UserDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户（API 约定 15.10）：options 已登录即可；新增为 user:create；
 * 列表 / 修改 / 停用 / 角色 / 重置密码为 system:manage
 */
@Tag(name = "用户")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final SysUserMapper sysUserMapper;
    private final UserAdminService userAdminService;
    private final UserDeletionService userDeletionService;

    public UserController(SysUserMapper sysUserMapper, UserAdminService userAdminService,
                          UserDeletionService userDeletionService) {
        this.sysUserMapper = sysUserMapper;
        this.userAdminService = userAdminService;
        this.userDeletionService = userDeletionService;
    }

    @Operation(summary = "用户选项（负责人选择等轻量场景，已登录即可）")
    @GetMapping("/options")
    public List<OptionItem> options() {
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getDeleted, 0L)
                        .eq(SysUser::getIsEnabled, true)
                        .orderByAsc(SysUser::getId))
                .stream()
                .map(u -> new OptionItem(u.getId(), u.getName(), u.getUsername()))
                .toList();
    }

    @Operation(summary = "用户列表",
            description = "权限：system:manage；过滤：unitId / role / keyword（登录名、姓名）/ isEnabled；手机号脱敏返回")
    @GetMapping
    @PreAuthorize("hasAuthority('system:manage')")
    public PageResult<SystemDtos.UserItem> page(@ModelAttribute SystemDtos.UserQuery query) {
        return userAdminService.page(query);
    }

    @Operation(summary = "新增用户",
            description = "权限：user:create；初始密码 8–64 位；roles 至少一个并按创建人角色矩阵校验；登录名唯一")
    @PostMapping
    @PreAuthorize("hasAuthority('user:create')")
    @AuditLog(action = "CREATE", resourceType = "user", resourceId = "#result.id")
    public SystemDtos.UserItem create(@Valid @RequestBody SystemDtos.UserCreateRequest request) {
        return userAdminService.create(request);
    }

    @Operation(summary = "本学院干事列表",
            description = "权限：user:create，且当前用户必须为 UNIT_OWNER；仅返回本人当前学院关联的纯 STAFF 账号；"
                    + "过滤：unitId / keyword（登录名、姓名）/ isEnabled；手机号脱敏返回")
    @GetMapping("/staff")
    @PreAuthorize("hasAuthority('user:create')")
    public PageResult<SystemDtos.UserItem> staffPage(@ModelAttribute SystemDtos.StaffQuery query) {
        return userAdminService.staffPage(query);
    }

    @Operation(summary = "修改或停用本学院干事",
            description = "权限：user:create，且当前用户必须为 UNIT_OWNER；可改 name / phone / unitId / isEnabled；"
                    + "unitId 必须属于本人学院；保留其他学院关系、角色、归属创建人与 createdBy；必须带 version；停用时吊销全部 refreshToken")
    @PatchMapping("/staff/{userId}")
    @PreAuthorize("hasAuthority('user:create')")
    @AuditLog(action = "UPDATE", resourceType = "user", resourceId = "#userId")
    public SystemDtos.UserItem patchStaff(@PathVariable Long userId, @RequestBody JsonNode body) {
        return userAdminService.patchStaff(userId, body);
    }

    @Operation(summary = "重置本学院干事密码",
            description = "权限：user:create，且当前用户必须为 UNIT_OWNER；新密码 8–64 位并必须带 version；"
                    + "重置后清除登录锁定并吊销全部 refreshToken")
    @PostMapping("/staff/{userId}/password-reset")
    @PreAuthorize("hasAuthority('user:create')")
    @AuditLog(action = "UPDATE", resourceType = "user", resourceId = "#userId")
    public void resetStaffPassword(@PathVariable Long userId,
                                   @Valid @RequestBody SystemDtos.StaffPasswordResetRequest request) {
        userAdminService.resetStaffPassword(userId, request);
    }

    @Operation(summary = "修改或停用用户",
            description = "权限：system:manage；可改 name / phone / email / unitId / isEnabled；登录名不可改；"
                    + "不能停用当前登录的管理员账号；停用时吊销全部 refreshToken；必须带 version")
    @PatchMapping("/{userId}")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "UPDATE", resourceType = "user", resourceId = "#userId")
    public SystemDtos.UserItem patch(@PathVariable Long userId, @RequestBody JsonNode body) {
        return userAdminService.patch(userId, body);
    }

    @Operation(summary = "删除用户（逻辑删除）",
            description = "权限：system:manage；必须带 version 和 1–500 字 reason；禁止删除本人、最后一名启用管理员及仍存在有效业务引用的用户；"
                    + "清理角色、学院关系、干事分配并吊销全部 refreshToken，保留业务历史字段")
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "DELETE", resourceType = "user", resourceId = "#userId", reason = "#request.reason",
            before = "deletionSnapshot(#userId)")
    public void delete(@PathVariable Long userId,
                       @Valid @RequestBody SystemDtos.UserDeleteRequest request) {
        userDeletionService.delete(userId, request);
    }

    @Operation(summary = "设置用户角色（敏感操作）",
            description = "权限：system:manage；必须带 reason（≤500 字，落审计）与 version；整体替换用户角色集；"
                    + "响应为 { user }；审计记录变更前后角色（PERMISSION_CHANGE）")
    @PutMapping("/{userId}/roles")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "PERMISSION_CHANGE", resourceType = "user", resourceId = "#userId",
            reason = "#request.reason", before = "#result.auditBefore", after = "#result.auditAfter")
    public SystemDtos.RoleChangeView updateRoles(@PathVariable Long userId,
                                                 @Valid @RequestBody SystemDtos.RolesRequest request) {
        return userAdminService.updateRoles(userId, request);
    }

    @Operation(summary = "重置用户密码",
            description = "权限：system:manage；新密码 8–64 位；重置后清除登录锁定并吊销全部 refreshToken（强制重新登录）")
    @PostMapping("/{userId}/password-reset")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "UPDATE", resourceType = "user", resourceId = "#userId")
    public void resetPassword(@PathVariable Long userId,
                              @Valid @RequestBody SystemDtos.PasswordResetRequest request) {
        userAdminService.resetPassword(userId, request.getPassword());
    }

    public SystemDtos.UserItem deletionSnapshot(Long userId) {
        return userDeletionService.snapshot(userId);
    }

    public record OptionItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            String username) {
    }
}
