package edu.fzrj.metrics.module.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 分片上传初始化请求（API 约定 8.2）
 */
@Data
public class MultipartInitRequest {

    @NotBlank(message = "fileName 不能为空")
    private String fileName;

    @NotNull(message = "size 不能为空")
    @Positive(message = "size 必须为正数")
    private Long size;

    private String mimeType;

    @NotBlank(message = "sha256 不能为空")
    private String sha256;

    @NotBlank(message = "bizType 不能为空")
    private String bizType;
}
