package edu.fzrj.metrics.module.submission.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.page.PageQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 填报与审核 DTO（API 约定 15.5、3.5 MetricValue）
 */
public final class SubmissionDtos {

    private SubmissionDtos() {
    }

    /** 指标值对象（请求与响应共用；rate / growthRate / increment 为服务端计算的只读字段） */
    @Data
    public static class MetricValue {
        private BigDecimal amount;
        private BigDecimal total;
        private BigDecimal numerator;
        private BigDecimal denominator;
        private BigDecimal percent;
        private Boolean achieved;
        private BigDecimal rate;
        private BigDecimal growthRate;
        private BigDecimal increment;
    }

    /** 新建填报草稿 */
    @Data
    public static class CreateRequest {
        @NotNull(message = "年度必填")
        private Integer year;

        @NotNull(message = "填报值必填")
        @Valid
        private MetricValue value;

        private String remark;
        private String sourceNote;
        private List<Long> fileIds;

        /** 管理员代填原因（敏感操作必填） */
        private String proxyReason;
    }

    /** 审核请求：result = APPROVED / REJECTED；approvedValue 用于核减 */
    @Data
    public static class ReviewRequest {
        @NotNull(message = "审核结果必填")
        private String result;

        private String comment;

        @Valid
        private MetricValue approvedValue;

        /** 管理员代审原因（敏感操作必填） */
        private String reason;
    }

    /** 批量审核通过 */
    @Data
    public static class BatchReviewRequest {
        @NotEmpty(message = "submissionIds 不能为空")
        private List<Long> submissionIds;

        private String comment;

        /** 管理员代审原因（敏感操作必填） */
        private String reason;
    }

    public record BatchItemResult(
            @JsonSerialize(using = ToStringSerializer.class) Long submissionId,
            Boolean success,
            Integer code,
            String message) {
    }

    public record BatchReviewResponse(List<BatchItemResult> results) {
    }

    /** 某 B 任务的填报记录查询 */
    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class SubmissionQuery extends PageQuery {
        private Integer year;
        private String status;
    }

    /** 待审核列表查询 */
    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class PendingQuery extends PageQuery {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long planId;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitId;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long indicatorId;
        private Integer year;
    }

    public record FileView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String fileName,
            Long size,
            String ext,
            String category,
            String mimeType) {
    }

    public record ReviewView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            Integer revisionNo,
            UserRef reviewer,
            String result,
            String comment,
            MetricValue approvedValue,
            Boolean isProxy,
            LocalDateTime createdAt) {
    }

    public record HistoryView(
            Integer revisionNo,
            MetricValue value,
            @JsonSerialize(contentUsing = ToStringSerializer.class) List<Long> fileIds,
            LocalDateTime submittedAt,
            UserRef submittedBy) {
    }

    public record IndicatorRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name,
            String level) {
    }

    public record UnitRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name) {
    }

    public record TaskRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code) {
    }

    /** 填报列表项（含待审核列表共用）；fileCount = 佐证材料份数（file_ref 统计） */
    public record ListItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            TaskRef task,
            IndicatorRef indicator,
            UnitRef unit,
            Integer year,
            String statCaliber,
            MetricValue value,
            String status,
            Integer revisionNo,
            LocalDateTime submittedAt,
            UserRef submittedBy,
            LocalDateTime reviewedAt,
            UserRef reviewer,
            Integer version,
            LocalDateTime createdAt,
            Integer fileCount) {
    }

    /** 填报详情（含材料、审核记录、历史快照） */
    public record Detail(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            TaskRef task,
            IndicatorRef indicator,
            UnitRef unit,
            Integer year,
            String statCaliber,
            MetricValue value,
            MetricValue approvedValue,
            String remark,
            String sourceNote,
            String status,
            Integer revisionNo,
            Boolean isProxy,
            List<FileView> files,
            List<ReviewView> reviews,
            List<HistoryView> histories,
            LocalDateTime submittedAt,
            UserRef submittedBy,
            LocalDateTime reviewedAt,
            UserRef reviewer,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
