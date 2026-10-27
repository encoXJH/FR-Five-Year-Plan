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
 * 执行单元
 */
@Data
@TableName("org_unit")
public class OrgUnit {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 单元编码，如 C01，用于 B 任务编号 */
    private String code;

    /** 单元名称 */
    private String name;

    /** 单元类型：COLLEGE 二级学院 / INTERNATIONAL_COLLEGE 国际学院 / MARXISM_COLLEGE 马克思主义学院 / SCHOOL_OFFICE 校办公室 */
    private String type;

    /** 排序号 */
    private Integer sort;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 是否可承接新 B 任务 */
    private Boolean isBAssignable;

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
