package edu.fzrj.metrics.module.milestone.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 待补做 DTO（API 约定 15.7、PRD 5.7 FR-M7-03）
 */
public final class MakeupDtos {

    private MakeupDtos() {
    }

    /** 补做完成请求 */
    @Data
    public static class CompleteRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
    }

    public record TaskRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code) {
    }

    /** 待补做视图 */
    public record View(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            TaskRef task,
            @JsonSerialize(using = ToStringSerializer.class) Long fromTaskYearId,
            Integer fromYear,
            Integer toYear,
            BigDecimal gapAmount,
            BigDecimal madeUpAmount,
            String status,
            @JsonSerialize(using = ToStringSerializer.class) Long adjustmentId,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
