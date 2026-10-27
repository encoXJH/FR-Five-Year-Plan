package edu.fzrj.metrics.module.adjustment.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.page.PageQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 指标调整 DTO（API 约定 15.6、16.5）
 */
public final class AdjustmentDtos {

    private AdjustmentDtos() {
    }

    // ---------------- 请求 ----------------

    /** 调整明细请求项：POSTPONE / TRANSFER 类用 fromYear + toYear + amount；TARGET_CHANGE 用 year + newTarget（+ 可选 targetIndicatorId） */
    @Data
    public static class ItemRequest {
        private Integer fromYear;
        private Integer toYear;
        private BigDecimal amount;
        private Integer year;
        private BigDecimal newTarget;

        /** 目标调整的指标（TARGET_CHANGE 可选，缺省为请求级 indicatorId，用于配套调整同计划内其他 A / A+） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long targetIndicatorId;
    }

    /** 发起调整申请 / 试算（preview 与 create 共用） */
    @Data
    public static class CreateRequest {
        @NotBlank(message = "调整类型必填")
        private String type;

        /** 转出 B 任务（POSTPONE / TRANSFER / POSTPONE_TRANSFER 必填） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long taskId;

        /** 接收执行单元（TRANSFER / POSTPONE_TRANSFER 必填） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long toUnitId;

        /** 目标调整的指标（TARGET_CHANGE 必填，A 或 A+） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long indicatorId;

        /** 新五年总量（TARGET_CHANGE 可选） */
        private BigDecimal newTotalTarget;

        @NotEmpty(message = "调整明细不能为空")
        @Valid
        private List<ItemRequest> items;

        @NotBlank(message = "调整原因必填")
        @Size(max = 2000, message = "调整原因不能超过 2000 字")
        private String reason;

        private List<Long> fileIds;
    }

    /** 审批处理：decision = APPROVE / REJECT；REJECT 必须填写 comment（60004） */
    @Data
    public static class DecisionRequest {
        @NotBlank(message = "审批决定必填")
        private String decision;

        @Size(max = 2000, message = "审批意见不能超过 2000 字")
        private String comment;

        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
    }

    /** 申请列表查询：scope = MINE 我发起的 / TODO 待我处理 / ALL 全部（按数据范围） */
    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class AdjustmentQuery extends PageQuery {
        private String scope;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long planId;
        private String status;
        private String type;
    }

    // ---------------- 视图 ----------------

    public record UnitRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name) {
    }

    public record TaskRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            Boolean isHistorical) {
    }

    public record IndicatorRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name,
            String statCaliber,
            String unitLabel) {
    }

    /** 快照条目：taskCode（移动类）或 indicatorCode（目标调整）；year 为 null 表示五年总量条目 */
    public record SnapshotEntry(String taskCode, String indicatorCode, Integer year, BigDecimal target) {
    }

    public record Snapshot(List<SnapshotEntry> before, List<SnapshotEntry> after) {
    }

    public record ItemView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            TaskRef fromTask,
            Integer fromYear,
            TaskRef toTask,
            UnitRef toUnit,
            Integer toYear,
            BigDecimal amount,
            BigDecimal newTarget,
            IndicatorRef targetIndicator) {
    }

    public record StepView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            Integer seq,
            String node,
            UserRef expectedApprover,
            UnitRef expectedUnit,
            String decision,
            String comment,
            LocalDateTime decidedAt,
            UserRef decidedBy) {
    }

    public record FileView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String fileName,
            Long size,
            String ext,
            String category) {
    }

    /** 申请列表项（items 为简要明细，供列表摘要展示） */
    public record ListItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            String type,
            TaskRef task,
            IndicatorRef indicator,
            UnitRef fromUnit,
            UnitRef toUnit,
            String status,
            String currentNode,
            UserRef applicant,
            String reason,
            List<ItemSummary> items,
            LocalDateTime effectiveAt,
            Integer version,
            LocalDateTime createdAt) {
    }

    /** 列表用简要明细项（TARGET_CHANGE 时 toYear 为被调整年度、newTarget 为新目标值） */
    public record ItemSummary(Integer fromYear, Integer toYear, BigDecimal amount, BigDecimal newTarget) {
    }

    /** 申请详情（含明细、审批环节、调整前后快照、附件） */
    public record Detail(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            String type,
            TaskRef task,
            IndicatorRef indicator,
            UnitRef fromUnit,
            UnitRef toUnit,
            String status,
            String currentNode,
            UserRef applicant,
            String reason,
            List<ItemView> items,
            List<StepView> steps,
            Snapshot snapshot,
            List<FileView> files,
            LocalDateTime effectiveAt,
            LocalDateTime withdrawnAt,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 试算响应：valid = 全部校验通过；errors 逐条列出；snapshot 为试算的调整前后目标 */
    public record PreviewResponse(Boolean valid, List<FieldError> errors, Snapshot snapshot,
                                  Boolean toTaskExists) {
    }
}
