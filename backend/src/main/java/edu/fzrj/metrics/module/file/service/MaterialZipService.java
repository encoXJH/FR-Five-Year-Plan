package edu.fzrj.metrics.module.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.dto.MaterialQuery;
import edu.fzrj.metrics.module.file.dto.MaterialRow;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.MaterialQueryMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 已授权材料 ID 的 ZIP 组装；权限在创建导出任务时校验。 */
@Service
public class MaterialZipService {

    private static final Pattern UNSAFE_PATH_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final DateTimeFormatter MANIFEST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+08:00'");

    private final MaterialQueryMapper materialQueryMapper;
    private final FileInfoMapper fileInfoMapper;
    private final StorageService storageService;

    public MaterialZipService(MaterialQueryMapper materialQueryMapper, FileInfoMapper fileInfoMapper,
                              StorageService storageService) {
        this.materialQueryMapper = materialQueryMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.storageService = storageService;
    }

    public byte[] buildZip(List<Long> fileIds) throws IOException {
        List<MaterialRow> rows = materialQueryMapper.selectMaterials(new MaterialQuery(
                null, null, null, null, null, null, null, null, null, fileIds, 0, 0));
        Map<Long, MaterialRow> metadata = new HashMap<>();
        rows.forEach(row -> metadata.putIfAbsent(row.getFileId(), row));

        List<ManifestItem> manifest = new ArrayList<>();
        Set<String> usedPaths = new HashSet<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Long fileId : fileIds) {
                FileInfo file = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                        .eq(FileInfo::getId, fileId)
                        .eq(FileInfo::getDeleted, 0L));
                if (file == null) {
                    throw new BizException(ErrorCode.FILE_NOT_FOUND, "材料文件不存在或已删除：" + fileId);
                }
                MaterialRow row = metadata.get(fileId);
                if (row == null) {
                    throw new BizException(ErrorCode.FILE_NOT_FOUND,
                            "材料已删除或不再符合导出条件：" + file.getFileName());
                }
                String path = uniquePath(row, file.getFileName(), usedPaths);
                try (InputStream input = storageService.read(file.getStorageKey())) {
                    zip.putNextEntry(new ZipEntry(path));
                    input.transferTo(zip);
                    zip.closeEntry();
                } catch (IOException e) {
                    BizException failure = new BizException(ErrorCode.FILE_NOT_FOUND,
                            "材料文件读取失败或已缺失：" + file.getFileName());
                    failure.initCause(e);
                    throw failure;
                }
                manifest.add(new ManifestItem(file.getFileName(), path, row));
            }
            writeManifest(zip, manifest);
            zip.finish();
        }
        return output.toByteArray();
    }

    private String uniquePath(MaterialRow row, String fileName, Set<String> usedPaths) {
        String directory = sanitize(row.getDirectionName(), "未归入建设方向") + "/"
                + sanitize(row.getIndicatorCode(), "未关联指标") + "/";
        String cleanName = sanitize(fileName, "未命名材料");
        String candidate = directory + cleanName;
        int sequence = 2;
        while (!usedPaths.add(candidate.toLowerCase(Locale.ROOT))) {
            int dot = cleanName.lastIndexOf('.');
            String base = dot > 0 ? cleanName.substring(0, dot) : cleanName;
            String extension = dot > 0 ? cleanName.substring(dot) : "";
            candidate = directory + base + " (" + sequence++ + ")" + extension;
        }
        return candidate;
    }

    private String sanitize(String value, String fallback) {
        String clean = value == null ? "" : UNSAFE_PATH_CHARS.matcher(value).replaceAll("_").trim();
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isEmpty() || ".".equals(clean) || "..".equals(clean)) {
            return fallback;
        }
        return clean;
    }

    private void writeManifest(ZipOutputStream zip, List<ManifestItem> items) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(0xEF);
        content.write(0xBB);
        content.write(0xBF);
        content.write("材料名,ZIP路径,指标编号,指标名称,任务编号,学院,审核通过时间\r\n".getBytes(StandardCharsets.UTF_8));
        for (ManifestItem item : items) {
            MaterialRow row = item.row();
            String line = String.join(",",
                    csv(item.fileName()),
                    csv(item.path()),
                    csv(row.getIndicatorCode()),
                    csv(row.getIndicatorName()),
                    csv(row.getTaskCode()),
                    csv(row.getUnitName()),
                    csv(row.getReviewedAt() == null ? null : MANIFEST_TIME.format(row.getReviewedAt()))) + "\r\n";
            content.write(line.getBytes(StandardCharsets.UTF_8));
        }
        zip.putNextEntry(new ZipEntry("manifest.csv"));
        zip.write(content.toByteArray());
        zip.closeEntry();
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record ManifestItem(String fileName, String path, MaterialRow row) {
    }
}
