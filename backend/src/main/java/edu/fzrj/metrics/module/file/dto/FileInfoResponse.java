package edu.fzrj.metrics.module.file.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件对象（API 约定 8.3）
 */
@Data
@AllArgsConstructor
public class FileInfoResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String fileName;
    private Long size;
    private String mimeType;
    private String category;
    private String bizType;
    private String status;
    private String transcodeStatus;
    private LocalDateTime uploadedAt;
    private UserRef uploadedBy;

    @Data
    @AllArgsConstructor
    public static class UserRef {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        private String name;
    }
}
