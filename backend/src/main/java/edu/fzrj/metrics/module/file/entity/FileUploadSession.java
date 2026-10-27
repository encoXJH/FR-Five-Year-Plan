package edu.fzrj.metrics.module.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分片上传会话
 */
@Data
@TableName("file_upload_session")
public class FileUploadSession {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 上传会话标识（UUID） */
    private String uploadId;

    /** 文件名 */
    private String fileName;

    /** 文件总大小（字节） */
    private Long size;

    /** MIME 类型 */
    private String mimeType;

    /** 整文件 SHA-256 */
    private String sha256;

    /** 分片大小（字节，默认 5MB） */
    private Integer partSize;

    /** 分片总数 */
    private Integer partCount;

    /** 已上传分片编号数组（断点续传） */
    private String uploadedParts;

    /** 会话状态：UPLOADING 上传中 / COMPLETED 已完成 / EXPIRED 已过期 */
    private String status;

    /** 会话过期时间 */
    private LocalDateTime expiresAt;

    /** 发起人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
