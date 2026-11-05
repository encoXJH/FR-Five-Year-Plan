package edu.fzrj.metrics.module.file.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 材料库联表查询行，避免在内存中加载全量关联记录。 */
@Data
public class MaterialRow {
    private Long fileId;
    private String fileName;
    private Long size;
    private String mimeType;
    private String category;
    private String bizType;
    private LocalDateTime uploadedAt;
    private Integer submissionYear;
    private Long taskId;
    private String taskCode;
    private Long indicatorId;
    private String indicatorCode;
    private String indicatorName;
    private String directionName;
    private LocalDateTime reviewedAt;
    private Long unitId;
    private String unitCode;
    private String unitName;
    private Long uploaderId;
    private String uploaderName;
}
