package edu.fzrj.metrics.module.indicator.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主要建设方向
 */
@Data
@TableName("direction")
public class Direction {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    /** 主体：SCHOOL 校级 / MAJOR_GROUP 专业群 */
    private String subjectType;

    /** 主体为专业群时必填，引用 major_group.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long majorGroupId;

    /** 建设方向（建设任务）名称 */
    private String name;

    /** S 级总负责人，引用 sys_user.id，必须具有 LEADER 角色 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long leaderId;

    /** 排序号 */
    private Integer sort;

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
