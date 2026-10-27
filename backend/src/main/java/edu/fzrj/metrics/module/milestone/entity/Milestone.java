package edu.fzrj.metrics.module.milestone.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 里程碑
 */
@Data
@TableName("milestone")
public class Milestone {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    /** 关联指标，引用 indicator.id（与 task_id 二选一） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long indicatorId;

    /** 关联任务，引用 task.id（与 indicator_id 二选一） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /** 里程碑名称 */
    private String name;

    /** 计划完成日期 */
    private LocalDate dueDate;

    /** 交付物要求 */
    private String deliverable;

    /** 关联的年度目标 */
    private Integer relatedYear;

    /** 状态：NOT_STARTED 未开始 / IN_PROGRESS 进行中 / COMPLETED 已完成 / OVERDUE 逾期 / POSTPONED 已延期 */
    private String status;

    /** 完成时间 */
    private LocalDateTime completedAt;

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
