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
 * 全校组织架构节点（独立于执行单元 org_unit）
 */
@Data
@TableName("org_department")
public class OrgDepartment {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 上级节点，引用 org_department.id；根节点为 0 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    /** 组织名称 */
    private String name;

    /** 组织分类：SCHOOL / LEADERSHIP / FUNCTIONAL / TEACHING / ENTERPRISE / SUPERMARKET / PROPERTY / CLINIC / BUSINESS */
    private String category;

    /** 同级排序号 */
    private Integer sort;

    /** 是否启用 */
    private Boolean isEnabled;

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
