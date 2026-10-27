package edu.fzrj.metrics.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 登录名 */
    private String username;

    /** 密码哈希（BCrypt） */
    private String passwordHash;

    /** 姓名 */
    private String name;

    /** 手机号（响应与日志中脱敏） */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 所属执行单元，引用 org_unit.id；校级角色为空 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long unitId;

    /** 飞书 open_id，绑定后唯一 */
    private String feishuOpenId;

    /** 飞书 union_id */
    private String feishuUnionId;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 连续登录失败次数 */
    private Integer loginFailCount;

    /** 账号锁定截止时间 */
    private LocalDateTime lockedUntil;

    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 创建人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 更新人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;

    /** 逻辑删除：0 未删除，否则为本行 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long deleted;
}
