package edu.fzrj.metrics.module.adjustment.entity;

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
 * 调整明细
 */
@Data
@TableName("adjustment_item")
public class AdjustmentItem {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 调整申请，引用 adjustment.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long adjustmentId;

    /** 转出 B 任务，引用 task.id；TARGET_CHANGE 时为空 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fromTaskId;

    /** 转出年度 */
    private Integer fromYear;

    /** 接收 B 任务（生效时回填；接收方原无该 A+ 任务时为新建任务），引用 task.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long toTaskId;

    /** 接收执行单元，引用 org_unit.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long toUnitId;

    /** 接收年度 */
    private Integer toYear;

    /** 调整数量（POSTPONE / TRANSFER 类） */
    private BigDecimal amount;

    /** 新目标值（TARGET_CHANGE 使用） */
    private BigDecimal newTarget;

    /** 目标调整的指标，引用 indicator.id（TARGET_CHANGE 使用） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetIndicatorId;

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
