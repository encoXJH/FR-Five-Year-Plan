package edu.fzrj.metrics.module.file.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * 文件分类、扩展名白名单、大小上限与魔数校验（API 约定 8.5）
 */
@Component
public class FileTypeRegistry {

    /** 分类 → 允许扩展名 */
    public static final Map<String, Set<String>> CATEGORY_EXTS = Map.of(
            "DOCUMENT", Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt"),
            "PDF", Set.of("pdf"),
            "IMAGE", Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp"),
            "VIDEO", Set.of("mp4", "mov", "avi", "wmv"),
            "ARCHIVE", Set.of("zip", "rar"));

    /** 分类 → 大小上限（字节） */
    public static final Map<String, Long> CATEGORY_MAX_SIZE = Map.of(
            "DOCUMENT", 50L * 1024 * 1024,
            "PDF", 50L * 1024 * 1024,
            "IMAGE", 20L * 1024 * 1024,
            "VIDEO", 500L * 1024 * 1024,
            "ARCHIVE", 200L * 1024 * 1024);

    /** 扩展名 → 分类 */
    public String categoryOf(String ext) {
        for (Map.Entry<String, Set<String>> entry : CATEGORY_EXTS.entrySet()) {
            if (entry.getValue().contains(ext)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public long maxSizeOf(String category) {
        return CATEGORY_MAX_SIZE.get(category);
    }

    /** 魔数校验：读取流前 16 字节判定；txt 等无魔数类型直接通过 */
    public boolean magicMatch(String ext, InputStream in) throws IOException {
        byte[] head = new byte[16];
        int n = readHead(in, head);
        return switch (ext) {
            case "pdf" -> startsWith(head, n, 0x25, 0x50, 0x44, 0x46);                       // %PDF
            case "png" -> startsWith(head, n, 0x89, 0x50, 0x4E, 0x47);
            case "jpg", "jpeg" -> startsWith(head, n, 0xFF, 0xD8, 0xFF);
            case "gif" -> startsWith(head, n, 0x47, 0x49, 0x46, 0x38);
            case "bmp" -> startsWith(head, n, 0x42, 0x4D);
            case "webp" -> startsWith(head, n, 0x52, 0x49, 0x46, 0x46) && at(head, n, 8, 0x57, 0x45, 0x42, 0x50); // RIFF....WEBP
            case "zip", "docx", "xlsx", "pptx" -> startsWith(head, n, 0x50, 0x4B, 0x03, 0x04);
            case "rar" -> startsWith(head, n, 0x52, 0x61, 0x72, 0x21);
            case "mp4", "mov" -> at(head, n, 4, 0x66, 0x74, 0x79, 0x70);                     // ....ftyp
            case "avi" -> startsWith(head, n, 0x52, 0x49, 0x46, 0x46) && at(head, n, 8, 0x41, 0x56, 0x49, 0x20); // RIFF....AVI
            case "wmv" -> startsWith(head, n, 0x30, 0x26, 0xB2, 0x75);
            case "doc", "xls", "ppt" -> startsWith(head, n, 0xD0, 0xCF, 0x11, 0xE0);         // OLE2
            case "txt" -> true;
            default -> false;
        };
    }

    private int readHead(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private boolean startsWith(byte[] head, int n, int... expected) {
        return at(head, n, 0, expected);
    }

    private boolean at(byte[] head, int n, int offset, int... expected) {
        if (n < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
