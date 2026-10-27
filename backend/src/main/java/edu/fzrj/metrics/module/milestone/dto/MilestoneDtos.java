package edu.fzrj.metrics.module.milestone.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.UserRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 里程碑 DTO（API 约定 15.7、PRD 5.7）
 */
public final class MilestoneDtos {

    private MilestoneDtos() {
    }

    /** 新建里程碑：indicatorId 与 taskId 二选一 */
    @Data
    public static class CreateRequest {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long indicatorId;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long taskId;

        @NotBlank(message = "里程碑名称必填")
        @Size(max = 200, message = "名称不能超过 200 字")
        private String name;

        @NotNull(message = "计划完成日期必填")
        private LocalDate dueDate;

        @Size(max = 2000, message = "交付物要求不能超过 2000 字")
        private String deliverable;

        /** 关联的年度目标（可选，须在计划起止年份内） */
        private Integer relatedYear;

        /** 初始状态：仅允许 NOT_STARTED / IN_PROGRESS，缺省 NOT_STARTED */
        private String status;
    }

    public record IndicatorRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name) {
    }

    public record TaskRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code) {
    }

    /** 里程碑视图 */
    public record View(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            IndicatorRef indicator,
            TaskRef task,
            String name,
            LocalDate dueDate,
            String deliverable,
            Integer relatedYear,
            String status,
            LocalDateTime completedAt,
            UserRef createdBy,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
