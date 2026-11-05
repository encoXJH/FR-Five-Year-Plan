package edu.fzrj.metrics.module.file.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.dto.MaterialRow;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.MaterialQueryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialZipServiceTest {

    @Mock private MaterialQueryMapper materialQueryMapper;
    @Mock private FileInfoMapper fileInfoMapper;
    @Mock private StorageService storageService;
    @InjectMocks private MaterialZipService materialZipService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FileInfo.class);
    }

    @Test
    void ZIP按方向指标归档并处理重名和清单() throws Exception {
        MaterialRow firstRow = materialRow(1L);
        MaterialRow secondRow = materialRow(2L);
        when(materialQueryMapper.selectMaterials(any())).thenReturn(List.of(firstRow, secondRow));
        FileInfo first = file(1L, "佐证.pdf", "materials/1.pdf");
        FileInfo second = file(2L, "佐证.pdf", "materials/2.pdf");
        when(fileInfoMapper.selectOne(any())).thenReturn(first, second);
        when(storageService.read("materials/1.pdf"))
                .thenReturn(new ByteArrayInputStream("第一份".getBytes(StandardCharsets.UTF_8)));
        when(storageService.read("materials/2.pdf"))
                .thenReturn(new ByteArrayInputStream("第二份".getBytes(StandardCharsets.UTF_8)));

        byte[] zipBytes = materialZipService.buildZip(List.of(1L, 2L));
        Map<String, byte[]> entries = unzip(zipBytes);

        String firstPath = "专业_建设/A01/佐证.pdf";
        String secondPath = "专业_建设/A01/佐证 (2).pdf";
        assertArrayEquals("第一份".getBytes(StandardCharsets.UTF_8), entries.get(firstPath));
        assertArrayEquals("第二份".getBytes(StandardCharsets.UTF_8), entries.get(secondPath));
        String manifest = new String(entries.get("manifest.csv"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains(firstPath));
        assertTrue(manifest.contains(secondPath));
        assertTrue(manifest.contains("A01-01"));
        assertTrue(manifest.contains("智能产业学院"));
        assertTrue(manifest.contains("2026-10-01T12:30:00+08:00"));
    }

    @Test
    void 物理文件缺失时明确指出材料名() throws Exception {
        when(materialQueryMapper.selectMaterials(any())).thenReturn(List.of(materialRow(1L)));
        when(fileInfoMapper.selectOne(any())).thenReturn(file(1L, "缺失佐证.pdf", "materials/missing.pdf"));
        when(storageService.read("materials/missing.pdf")).thenThrow(new NoSuchFileException("missing"));

        BizException error = assertThrows(BizException.class, () -> materialZipService.buildZip(List.of(1L)));

        assertEquals(80004, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("缺失佐证.pdf"));
    }

    private MaterialRow materialRow(Long fileId) {
        MaterialRow row = new MaterialRow();
        row.setFileId(fileId);
        row.setDirectionName("专业/建设");
        row.setIndicatorCode("A01");
        row.setIndicatorName("关键产出指标");
        row.setTaskCode("A01-01");
        row.setUnitName("智能产业学院");
        row.setReviewedAt(LocalDateTime.of(2026, 10, 1, 12, 30));
        return row;
    }

    private FileInfo file(Long id, String fileName, String storageKey) {
        FileInfo file = new FileInfo();
        file.setId(id);
        file.setFileName(fileName);
        file.setStorageKey(storageKey);
        file.setDeleted(0L);
        return file;
    }

    private Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                zip.transferTo(content);
                entries.put(entry.getName(), content.toByteArray());
            }
        }
        return entries;
    }
}
