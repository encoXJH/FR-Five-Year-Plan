package edu.fzrj.metrics.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 每个角色的权限矩阵版本，空权限集也能与未初始化配置区分。 */
@Data
@TableName("role_permission_version")
public class RolePermissionVersion {

    @TableId(type = IdType.INPUT)
    private String role;

    private Integer version;

    private LocalDateTime updatedAt;

    private Long updatedBy;
}
