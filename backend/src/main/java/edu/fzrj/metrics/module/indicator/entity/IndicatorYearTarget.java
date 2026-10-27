package edu.fzrj.metrics.module.indicator.entity;

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
 * 指标年度目标与完成情况缓存
 */
@Data
@TableName("indicator_year_target")
public class IndicatorYearTarget {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 指标，引用 indicator.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long indicatorId;

    /** 年度 */
    private Integer year;

    /** 年度目标（BOOLEAN 口径：计划完成年存 1，其他年份 NULL） */
    private BigDecimal target;

    /** 缓存：年度完成值（审核通过口径，按统计口径计算） */
    private BigDecimal actualValue;

    /** 缓存：RATIO 口径汇总分子 */
    private BigDecimal actualNumerator;

    /** 缓存：RATIO 口径汇总分母 */
    private BigDecimal actualDenominator;

    /** 缓存：STOCK / GROWTH 口径的年末总量 */
    private BigDecimal actualTotal;

    /** 缓存：完成率（小数） */
    private BigDecimal completionRate;

    /** 缓存：达成状态：NOT_STARTED / IN_PROGRESS / ACHIEVED / EXCEEDED / NOT_ACHIEVED / LAGGING */
    private String achieveStatus;

    /** 缓存最近计算时间 */
    private LocalDateTime calculatedAt;

    /** A 汇总方式为 MANUAL 时的人工认定完成值 */
    private BigDecimal manualActualValue;

    /** 人工认定说明 */
    private String manualNote;

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
