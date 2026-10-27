package edu.fzrj.metrics.module.file.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * 文件存储抽象（LOCAL 本地 / MINIO 对象存储，P1）
 */
public interface StorageService {

    /** 保存文件内容到指定存储键 */
    void save(InputStream in, String storageKey) throws IOException;

    /** 读取文件内容 */
    InputStream read(String storageKey) throws IOException;

    /** 删除文件 */
    void delete(String storageKey) throws IOException;

    /** 分片临时目录 */
    Path partDir(String uploadId);

    /** 删除分片临时目录 */
    void deletePartDir(String uploadId) throws IOException;
}
