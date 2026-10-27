package edu.fzrj.metrics.module.milestone.entity;

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
 * 待补做
 */
@Data
@TableName("makeup")
public class Makeup {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** B 任务，引用 task.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /** 未达标的 B 年度，引用 task_year.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fromTaskYearId;

    /** 未达标年度 */
    private Integer fromYear;

    /** 补做年度 */
    private Integer toYear;

    /** 缺口数量 */
    private BigDecimal gapAmount;

    /** 已补做数量 */
    private BigDecimal madeUpAmount;

    /** 状态：PENDING 待补做 / DONE 已补做 / CANCELLED 已取消 */
    private String status;

    /** 关联的延期调整单，引用 adjustment.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long adjustmentId;

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
