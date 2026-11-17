package edu.fzrj.metrics.module.system.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 角色功能权限配置（四类固定角色，不包含数据范围）。 */
public final class RolePermissionDtos {

    private RolePermissionDtos() {
    }

    public record PermissionItem(
            String code,
            String name,
            String group,
            String description,
            List<String> configurableRoles,
            List<String> lockedRoles) {
    }

    public record RoleItem(
            String role,
            String roleName,
            List<String> permissions,
            Integer version,
            @JsonIgnore List<String> auditBefore,
            @JsonIgnore List<String> auditAfter) {
        public RoleItem(String role, String roleName, List<String> permissions, Integer version) {
            this(role, roleName, permissions, version, null, null);
        }

        public RoleItem withAudit(List<String> before, List<String> after) {
            return new RoleItem(role, roleName, permissions, version, before, after);
        }
    }

    public record MatrixView(List<RoleItem> roles, List<PermissionItem> permissions) {
    }

    @Data
    public static class UpdateRequest {
        @NotNull(message = "permissions 不能为空，可为空数组")
        private List<String> permissions;

        @NotBlank(message = "修改角色权限必须填写原因（reason）")
        @Size(max = 500, message = "原因不超过 500 字")
        private String reason;

        @NotNull(message = "缺少 version（乐观锁版本号）")
        @Min(value = 0, message = "version 不能小于 0")
        private Integer version;
    }
}
