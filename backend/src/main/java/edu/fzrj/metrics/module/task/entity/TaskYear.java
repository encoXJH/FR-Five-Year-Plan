package edu.fzrj.metrics.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * B 任务年度
 */
@Data
@TableName("task_year")
public class TaskYear {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** B 任务，引用 task.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /** 年度 */
    private Integer year;

    /** 冗余：计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    /** 冗余：指标，引用 indicator.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long indicatorId;

    /** 冗余：执行单元，引用 org_unit.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long unitId;

    /** 当前生效的年度目标（BOOLEAN 口径：计划完成年存 1） */
    private BigDecimal target;

    /** 下发时的原始年度目标（调整后不变，便于追溯） */
    private BigDecimal originalTarget;

    /** 目标版本号，每次调整生效后 +1 */
    private Integer targetVersion;

    /** 调整审批中冻结的额度（防止同一额度重复申请） */
    private BigDecimal frozenAmount;

    /** 缓存：年度完成值（审核通过口径） */
    private BigDecimal actualValue;

    /** 缓存：RATIO 口径分子 */
    private BigDecimal actualNumerator;

    /** 缓存：RATIO 口径分母 */
    private BigDecimal actualDenominator;

    /** 缓存：STOCK / GROWTH 口径最新审核通过总量 */
    private BigDecimal actualTotal;

    /** 缓存：是否达标 */
    private Boolean isAchieved;

    /** 缓存：完成率（小数） */
    private BigDecimal completionRate;

    /** 年度状态：NOT_ACTIVATED 未激活 / IN_PROGRESS 进行中 / PENDING_REVIEW 待审核 / ADJUSTING 调整审批中 / ACHIEVED 已达标 / NOT_ACHIEVED 未达标 / MAKEUP_PENDING 待补做 / MADE_UP 已补做 */
    private String yearStatus;

    /** 进入 ADJUSTING 前的状态，审批结束后恢复用 */
    private String statusBeforeAdjusting;

    /** 年度核算锁定时间 */
    private LocalDateTime settledAt;

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
