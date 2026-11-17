package edu.fzrj.metrics.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 四类固定角色的功能权限关联。 */
@Data
@TableName("role_permission")
public class RolePermission {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String role;

    private String permission;

    private LocalDateTime createdAt;

    private Long createdBy;
}
