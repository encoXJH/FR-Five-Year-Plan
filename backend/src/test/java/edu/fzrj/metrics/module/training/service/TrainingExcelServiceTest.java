package edu.fzrj.metrics.module.training.service;

import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingExcelServiceTest {

    private final TrainingExcelService excelService = new TrainingExcelService();

    @Test
    void 导出保留四行表头并从第五行写入57列() throws Exception {
        TrainingFieldSchema.ValidationResult validated = TrainingFieldSchema.validate(
                TrainingFieldSchemaTest.fullData(), "田明月", true, "data");
        TrainingDtos.Project project = new TrainingDtos.Project(101L, 1, validated.data(),
                "APPROVED", "UNIT_OWNER", null, 1, 99L, null, false, false, null);
        TrainingDtos.SheetSummary sheet = new TrainingDtos.SheetSummary(1L, 2L, "2025—2026学年职业培训信息采集",
                2025, 2026, 2024, 14L, 99L, "APPROVED", 1, 1, null,
                "产业AI人才服务中心", "田明月", "UNIT_OWNER", null, 1,
                99L, "田明月", null, false, false);
        TrainingDtos.Detail detail = new TrainingDtos.Detail(sheet, TrainingFieldSchema.fields(), List.of(project), List.of());

        byte[] bytes = excelService.build(detail);

        assertTrue(bytes.length > 0);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheetView = workbook.getSheet("职业培训信息表");
            assertNotNull(sheetView);
            assertEquals("具体培训项目名称*", sheetView.getRow(1).getCell(0).getStringCellValue());
            assertEquals(57, sheetView.getRow(4).getLastCellNum());
            assertEquals("人工智能应用培训", sheetView.getRow(4).getCell(0).getStringCellValue());
            assertEquals("2025-09-01", sheetView.getRow(4).getCell(4).getStringCellValue());
            assertEquals(6, (int) sheetView.getRow(4).getCell(31).getNumericCellValue());
            assertEquals("田明月", sheetView.getRow(4).getCell(56).getStringCellValue());
        }
    }
}
