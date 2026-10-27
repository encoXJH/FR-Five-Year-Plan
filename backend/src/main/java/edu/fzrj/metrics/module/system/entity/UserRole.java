package edu.fzrj.metrics.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户角色
 */
@Data
@TableName("user_role")
public class UserRole {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 用户，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 角色：ADMIN 平台管理员 / LEADER 校领导 / SCHOOL_OWNER 校级项目负责人 / UNIT_OWNER 学院项目负责人 */
    private String role;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 创建人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;
}
