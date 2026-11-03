package edu.fzrj.metrics.module.file.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public final class MaterialDtos {

    private MaterialDtos() {
    }

    public record IndicatorRef(@JsonSerialize(using = ToStringSerializer.class) Long id, String code, String name) {
    }

    /** 材料库筛选/关联用的指标选项，附所属建设方向便于分组 */
    public record IndicatorOption(@JsonSerialize(using = ToStringSerializer.class) Long id, String code,
                                  String name, String directionName) {
    }

    public record UnitRef(@JsonSerialize(using = ToStringSerializer.class) Long id, String code, String name) {
    }

    public record UserRef(@JsonSerialize(using = ToStringSerializer.class) Long id, String name) {
    }

    public record MaterialItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String fileName,
            Long size,
            String mimeType,
            String category,
            String bizType,
            LocalDateTime uploadedAt,
            Integer year,
            String taskCode,
            IndicatorRef indicator,
            UnitRef unit,
            UserRef uploadedBy
    ) {
    }

    public record BindRequest(
            @NotNull Long indicatorId,
            @NotEmpty List<Long> fileIds
    ) {
    }

    /** 按关键产出指标统计的材料数量 */
    @Data
    public static class IndicatorMaterialCount {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long indicatorId;
        private Long materialCount;
    }
}
