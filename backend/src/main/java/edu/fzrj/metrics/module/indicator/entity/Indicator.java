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
 * 指标（A / A+）
 */
@Data
@TableName("indicator")
public class Indicator {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    /** 所属建设方向，引用 direction.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long directionId;

    /** A+ 指向所属 A，引用 indicator.id；A 为空 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    /** 层级：A / A_PLUS */
    private String level;

    /** 指标编号，同一计划内唯一，如 A01、A01-2 */
    private String code;

    /** 关键产出指标名称 */
    private String name;

    /** 指标性质：CONSTRAINT 约束性 / DEVELOPMENT 发展性 */
    private String nature;

    /** 计量类型：NUMBER / BOOLEAN / PERCENT_QUANTIFIABLE / PERCENT_DIRECT */
    private String measureType;

    /** 统计口径：INCREMENT / STOCK / GROWTH / RATIO / DIRECT_PERCENT / BOOLEAN */
    private String statCaliber;

    /** 计量单位：项、个、人、% 等（字典维护） */
    private String unitLabel;

    /** 达成方向：HIGHER_BETTER 越大越好 / LOWER_BETTER 越小越好 */
    private String targetDirection;

    /** 汇总方式（仅 A 有效）：AUTO_SUM 自动汇总 / MANUAL 手工认定 */
    private String rollupMode;

    /** 是否可量化（仅 A 有效）；不可量化 A 不生成任务 */
    private Boolean isQuantifiable;

    /** 指标填写说明（统计口径、认定标准） */
    private String description;

    /** 佐证材料要求说明 */
    private String evidenceRequirement;

    /** 必须上传的材料最少数量 */
    private Integer minFileCount;

    /** 基线值（基线年实际值，GROWTH / STOCK 必填） */
    private BigDecimal baselineValue;

    /** 五年目标总量（GROWTH / STOCK 口径为末年应达总量；BOOLEAN 为 1） */
    private BigDecimal totalTarget;

    /** 校级项目负责人，引用 sys_user.id；A+ 为空时沿用上级 A */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;

    /** 排序号 */
    private Integer sort;

    /** 指标状态：DRAFT 草稿 / FINALIZED 已定稿 / DISTRIBUTED 已下发 */
    private String status;

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
