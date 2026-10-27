package edu.fzrj.metrics.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * B 任务
 */
@Data
@TableName("task")
public class Task {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    /** 所属 A+；历史数据可能指向原直承 A，引用 indicator.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long indicatorId;

    /** 冗余：所属 A 类指标，引用 indicator.id，便于汇总与数据权限 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long topIndicatorId;

    /** 承接执行单元，引用 org_unit.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long unitId;

    /** 任务编号，同一计划内唯一，格式 {指标编号}-{单元编码}，如 A01-2-C03 */
    private String code;

    /** 学院项目负责人，引用 sys_user.id（下发前必填，50002） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;

    /** 任务状态：PENDING_DISTRIBUTION 待下发 / DISTRIBUTED 已下发 / IN_PROGRESS 执行中 / TRANSFERRED_OUT 已全部转出 / CLOSED 已结束 */
    private String status;

    /** 下发时间 */
    private LocalDateTime distributedAt;

    /** 下发操作人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long distributedBy;

    /** 学院确认接收时间 */
    private LocalDateTime acceptedAt;

    /** 确认接收人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long acceptedBy;

    /** 转移时自动新建的任务记录来源调整单，引用 adjustment.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceAdjustmentId;

    /** 是否体系改造前的只读历史任务 */
    private Boolean isHistorical = false;

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
