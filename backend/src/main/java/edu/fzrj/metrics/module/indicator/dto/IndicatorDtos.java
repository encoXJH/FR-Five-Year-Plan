package edu.fzrj.metrics.module.indicator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.UserRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 指标 DTO（API 约定 15.3）
 */
public final class IndicatorDtos {

    private IndicatorDtos() {
    }

    /** 年度目标项：BOOLEAN 口径 target 为 true / null，其余口径为数字 */
    @Data
    public static class YearTargetItem {
        @NotNull(message = "年度必填")
        private Integer year;
        private Object target;
    }

    /** 新增 A / A+ */
    @Data
    public static class CreateRequest {
        @NotBlank(message = "层级必填")
        private String level;

        /** A+ 必填：所属 A */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long parentId;

        /** 草稿 A 可暂空；仅 ADMIN 可在创建时指定，A+ 由服务端继承 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long directionId;

        /** 编号，可空（空则系统生成：A01 / A01-1） */
        private String code;

        @NotBlank(message = "指标名称必填")
        @Size(max = 200, message = "名称不能超过 200 字")
        private String name;

        @NotBlank(message = "指标性质必填")
        private String nature;

        private String measureType;

        private String statCaliber;

        @JsonAlias("unit")
        private String unitLabel;

        private String targetDirection;

        /** 汇总方式（可量化 A 必填） */
        private String rollupMode;

        /** 是否可量化（仅 A 必填）；不可量化 A 不设置量化字段 */
        private Boolean isQuantifiable;

        @Size(max = 2000, message = "填写说明不能超过 2000 字")
        private String description;

        @Size(max = 2000, message = "佐证材料要求不能超过 2000 字")
        private String evidenceRequirement;

        private Integer minFileCount;

        private BigDecimal baselineValue;

        private BigDecimal totalTarget;

        /** 校级项目负责人（A+ 为 null 时沿用上级 A） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long ownerId;

        private Integer sort;

        @Valid
        private List<YearTargetItem> yearTargets;
    }

    /** 设置五年总量与年度目标 */
    @Data
    public static class YearTargetsPutRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;

        private BigDecimal totalTarget;

        private BigDecimal baselineValue;

        @NotEmpty(message = "年度目标必填")
        @Valid
        private List<YearTargetItem> yearTargets;
    }

    /** 定稿请求 */
    @Data
    public static class FinalizeRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
    }

    /** ADMIN 设置草稿 A 的 S 归属 */
    @Data
    public static class DirectionAssignRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long directionId;
    }

    /** 树节点 / 列表项 */
    public record TreeNode(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long directionId,
            String directionName,
            @JsonSerialize(using = ToStringSerializer.class) Long parentId,
            String level,
            String code,
            String name,
            String nature,
            String measureType,
            String statCaliber,
            String unitLabel,
            String targetDirection,
            String rollupMode,
            Boolean isQuantifiable,
            UserRef owner,
            BigDecimal baselineValue,
            BigDecimal totalTarget,
            List<YearTargetView> yearTargets,
            String status,
            Integer sort,
            Integer version,
            List<TreeNode> children) {
    }

    /** A+ 概要（A 详情下挂） */
    public record APlusSummary(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name,
            String statCaliber,
            UserRef owner,
            BigDecimal totalTarget,
            String status) {
    }

    public record IndicatorRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name) {
    }

    public record UnitRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name) {
    }

    public record YearTargetView(Integer year, Object target) {
    }

    /** B 任务概要；历史 A 直承任务也以 level=B 只读返回 */
    public record BSummary(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String level,
            String code,
            UnitRef unit,
            UserRef owner,
            String status,
            Boolean isHistorical) {
    }

    /** 指标详情 */
    public record Detail(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            @JsonSerialize(using = ToStringSerializer.class) Long directionId,
            String directionName,
            @JsonSerialize(using = ToStringSerializer.class) Long parentId,
            IndicatorRef parent,
            String level,
            String code,
            String name,
            String nature,
            String measureType,
            String statCaliber,
            String unitLabel,
            String targetDirection,
            String rollupMode,
            Boolean isQuantifiable,
            String description,
            String evidenceRequirement,
            Integer minFileCount,
            BigDecimal baselineValue,
            BigDecimal totalTarget,
            UserRef owner,
            Integer sort,
            String status,
            List<YearTargetView> yearTargets,
            List<APlusSummary> children,
            List<BSummary> bSummaries,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    // ---------------- 导入 ----------------

    public record ImportRowResult(Integer rowNum, String level, String code, String name,
                                  Boolean valid, List<String> errors) {
    }

    public record ImportPreviewResponse(String importId, Integer totalRows, Integer validRows,
                                        Integer invalidRows, List<ImportRowResult> rows) {
    }

    @Data
    public static class ImportConfirmRequest {
        @NotBlank(message = "importId 必填")
        private String importId;
    }

    public record ImportConfirmResponse(Integer directionCount, Integer aCount, Integer aPlusCount) {
    }
}
