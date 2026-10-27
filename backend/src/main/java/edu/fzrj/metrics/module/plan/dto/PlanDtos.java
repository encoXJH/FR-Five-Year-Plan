package edu.fzrj.metrics.module.plan.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.UserRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计划 / 模板相关 DTO（API 约定 15.2）
 */
public final class PlanDtos {

    private PlanDtos() {
    }

    /** 新建计划（source：BLANK / TEMPLATE / COPY） */
    @Data
    public static class CreateRequest {
        @NotBlank(message = "计划名称必填")
        @Size(max = 200, message = "名称不能超过 200 字")
        private String name;

        @NotNull(message = "起始年份必填")
        private Integer startYear;

        @NotNull(message = "结束年份必填")
        private Integer endYear;

        /** 基线年，默认起始年 -1 */
        private Integer baselineYear;

        /** 年度核算截止月-日，默认 01-15 */
        @Pattern(regexp = "^(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$", message = "核算截止日格式应为 MM-DD")
        private String settleDeadline;

        @NotBlank(message = "新建方式必填")
        private String source;

        /** source=TEMPLATE 时必填 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long templateId;

        /** source=COPY 时必填 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long sourcePlanId;

        /** COPY 时是否带分解数据（阶段 2 仅复制结构，字段预留） */
        private Boolean withAllocation;

        private Boolean isReceiverConfirmRequired;
    }

    /** 业务动作请求体（activate / archive / finalize 等）：乐观锁版本号 */
    @Data
    public static class VersionRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
    }

    /** 另存为模板 */
    @Data
    public static class SaveAsTemplateRequest {
        @NotBlank(message = "模板名称必填")
        @Size(max = 200, message = "名称不能超过 200 字")
        private String name;

        @Size(max = 2000, message = "说明不能超过 2000 字")
        private String description;
    }

    /** 计划列表项（首页卡片） */
    public record ListItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            Integer startYear,
            Integer endYear,
            Integer baselineYear,
            String status,
            String source,
            LocalDateTime finalizedAt,
            /** 整体完成率（阶段 2 无任务数据，恒为 null，阶段 6 补齐） */
            BigDecimal completionRate,
            LocalDateTime createdAt) {
    }

    /** 计划详情 */
    public record Detail(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            Integer startYear,
            Integer endYear,
            Integer baselineYear,
            String settleDeadline,
            String status,
            String source,
            @JsonSerialize(using = ToStringSerializer.class) Long templateId,
            @JsonSerialize(using = ToStringSerializer.class) Long sourcePlanId,
            Boolean isReceiverConfirmRequired,
            LocalDateTime finalizedAt,
            UserRef finalizedBy,
            List<YearItem> years,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record YearItem(Integer year, String status) {
    }

    /** 模板列表项 / 详情 */
    public record TemplateItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            String description,
            @JsonSerialize(using = ToStringSerializer.class) Long sourcePlanId,
            String sourcePlanName,
            Integer durationYears,
            Integer directionCount,
            Integer indicatorCountA,
            Integer indicatorCountAPlus,
            Long referenceCount,
            Boolean isEnabled,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record TemplateDetail(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            String description,
            Object content,
            @JsonSerialize(using = ToStringSerializer.class) Long sourcePlanId,
            Boolean isEnabled,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 复制模板 */
    @Data
    public static class CopyTemplateRequest {
        @Size(max = 200, message = "名称不能超过 200 字")
        private String name;
    }
}
