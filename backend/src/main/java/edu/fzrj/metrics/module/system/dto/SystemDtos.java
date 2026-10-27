package edu.fzrj.metrics.module.system.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.page.PageQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 系统管理 DTO（API 约定 15.10）：执行单元 / 专业群 / 用户 / 字典 / 系统参数。
 */
public final class SystemDtos {

    private SystemDtos() {
    }

    // ---------------- 执行单元 ----------------

    @Data
    public static class OrgUnitCreateRequest {
        @NotBlank(message = "单元编码必填")
        @Size(max = 16, message = "单元编码不超过 16 字")
        private String code;
        @NotBlank(message = "单元名称必填")
        @Size(max = 200, message = "单元名称不超过 200 字")
        private String name;
        @NotBlank(message = "单元类型必填")
        private String type;
        private Integer sort;
        private Boolean isBAssignable;
    }

    public record OrgUnitView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code, String name, String type, Integer sort, Boolean isEnabled,
            Boolean isBAssignable, Integer version) {
    }

    // ---------------- 专业群 ----------------

    @Data
    public static class MajorGroupCreateRequest {
        @NotBlank(message = "专业群名称必填")
        @Size(max = 200, message = "专业群名称不超过 200 字")
        private String name;
        @NotNull(message = "所属学院必填")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitId;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long ownerId;
    }

    public record MajorGroupView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            @JsonSerialize(using = ToStringSerializer.class) Long unitId,
            String unitName,
            @JsonSerialize(using = ToStringSerializer.class) Long ownerId,
            String ownerName,
            Boolean isEnabled, Integer version) {
    }

    // ---------------- 用户 ----------------

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class UserQuery extends PageQuery {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitId;
        private String role;
        private String keyword;
        private Boolean isEnabled;
    }

    @Data
    public static class UserCreateRequest {
        @NotBlank(message = "登录名必填")
        @Size(max = 64, message = "登录名不超过 64 字")
        private String username;
        @NotBlank(message = "初始密码必填")
        @Size(min = 8, max = 64, message = "密码长度 8–64 位")
        private String password;
        @NotBlank(message = "姓名必填")
        @Size(max = 64, message = "姓名不超过 64 字")
        private String name;
        @Size(max = 32, message = "手机号不超过 32 字")
        private String phone;
        @Size(max = 128, message = "邮箱不超过 128 字")
        private String email;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitId;
        @NotEmpty(message = "至少指定一个角色")
        private List<String> roles;
    }

    @Data
    public static class RolesRequest {
        @NotEmpty(message = "角色列表不能为空（清空角色传停用）")
        private List<String> roles;
        @NotBlank(message = "修改用户角色必须填写原因（reason）")
        @Size(max = 500, message = "原因不超过 500 字")
        private String reason;
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
    }

    @Data
    public static class PasswordResetRequest {
        @NotBlank(message = "新密码必填")
        @Size(min = 8, max = 64, message = "密码长度 8–64 位")
        private String password;
    }

    public record UserItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String username, String name, String phone, String email,
            @JsonSerialize(using = ToStringSerializer.class) Long unitId,
            String unitName,
            List<String> roles,
            Boolean isEnabled, LocalDateTime lastLoginAt, Integer version) {
    }

    /** 角色变更响应：含审计用前后快照（不回传给前端） */
    public record RoleChangeView(
            UserItem user,
            @com.fasterxml.jackson.annotation.JsonIgnore List<String> auditBefore,
            @com.fasterxml.jackson.annotation.JsonIgnore List<String> auditAfter) {
    }

    // ---------------- 系统参数 ----------------

    public record SettingItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String key, String value, String valueType, String description, Integer version) {
    }

    /** 参数列表响应：{ items }；auditBefore/auditAfter 仅供审计切面读取，不序列化给前端 */
    public record SettingListView(
            List<SettingItem> items,
            @com.fasterxml.jackson.annotation.JsonIgnore Map<String, String> auditBefore,
            @com.fasterxml.jackson.annotation.JsonIgnore Map<String, String> auditAfter) {
    }

    @Data
    public static class SettingPatchRequest {
        @NotEmpty(message = "items 不能为空")
        @Valid
        private List<SettingPatchItem> items;
    }

    @Data
    public static class SettingPatchItem {
        @NotBlank(message = "参数键必填")
        private String key;
        @NotNull(message = "参数值必填")
        private String value;
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
    }

    // ---------------- 字典 ----------------

    @Data
    public static class DictPutRequest {
        @NotEmpty(message = "字典项不能为空（清空字典请逐项停用）")
        @Valid
        private List<DictPutItem> items;
    }

    @Data
    public static class DictPutItem {
        /** 已存在的字典项 id（不传 = 新增，按 code 匹配已有项） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        @NotBlank(message = "字典编码必填")
        @Size(max = 64, message = "字典编码不超过 64 字")
        private String code;
        @NotBlank(message = "显示名必填")
        @Size(max = 200, message = "显示名不超过 200 字")
        private String label;
        private Integer sort;
        private Boolean isEnabled;
    }
}
