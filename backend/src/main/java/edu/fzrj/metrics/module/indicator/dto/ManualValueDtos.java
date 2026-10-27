package edu.fzrj.metrics.module.indicator.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MANUAL 汇总方式 A 指标的人工认定（PRD 4.5：A 为 MANUAL 时由校级项目负责人手工认定）
 */
public final class ManualValueDtos {

    private ManualValueDtos() {
    }

    @Data
    public static class RecognizeRequest {
        @NotNull(message = "认定值必填")
        private BigDecimal value;

        /** 认定说明（可选，≤500 字，写入审计 reason） */
        @Size(max = 500, message = "认定说明不超过 500 字")
        private String note;

        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
    }

    public record View(
            @JsonSerialize(using = ToStringSerializer.class) Long indicatorId,
            Integer year,
            BigDecimal target,
            BigDecimal actualValue,
            BigDecimal completionRate,
            String achieveStatus,
            BigDecimal manualActualValue,
            String manualNote,
            LocalDateTime calculatedAt,
            Integer version) {
    }
}
