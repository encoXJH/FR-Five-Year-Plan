package edu.fzrj.metrics.module.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件
 */
@Data
@TableName("file_info")
public class FileInfo {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 原始文件名 */
    private String fileName;

    /** 扩展名（小写，不含点） */
    private String ext;

    /** 文件大小（字节） */
    private Long size;

    /** MIME 类型 */
    private String mimeType;

    /** 分类：DOCUMENT 文档 / PDF / IMAGE 图片 / VIDEO 视频 / ARCHIVE 压缩包 */
    private String category;

    /** 用途：SUBMISSION_ATTACHMENT 填报材料 / ADJUSTMENT_ATTACHMENT 调整附件 / MILESTONE_ATTACHMENT 里程碑附件 / IMPORT_FILE 导入文件 */
    private String bizType;

    /** 状态：TEMP 临时（超 24h 未绑定自动清理）/ BOUND 已绑定 */
    private String status;

    /** 存储类型：LOCAL 本地 / MINIO 对象存储 */
    private String storageType;

    /** 存储键（路径或对象键） */
    private String storageKey;

    /** SHA-256（秒传与完整性校验） */
    private String sha256;

    /** 视频转码状态：PROCESSING 转码中 / SUCCEEDED 成功 / FAILED 失败 */
    private String transcodeStatus;

    /** 预览转换产物的存储键 */
    private String previewKey;

    /** 上传人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long uploadedBy;

    /** 上传时间 */
    private LocalDateTime uploadedAt;

    /** 绑定时间 */
    private LocalDateTime boundAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 创建人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 更新人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;

    /** 逻辑删除：0 未删除，否则为本行 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long deleted;
}
