package edu.fzrj.metrics.module.submission.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

public final class YearUploadDtos {

    private YearUploadDtos() {
    }

    /** 当年已上传（已提交审核）的指标填报清单项 */
    public record UploadItem(
            @JsonSerialize(using = ToStringSerializer.class) Long submissionId,
            @JsonSerialize(using = ToStringSerializer.class) Long indicatorId,
            String indicatorCode,
            String indicatorName,
            String taskCode,
            String unitName,
            LocalDateTime submittedAt,
            String submittedByName,
            String status
    ) {
    }
}
