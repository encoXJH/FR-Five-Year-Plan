package edu.fzrj.metrics.module.training.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class TrainingExcelService {

    private static final String TEMPLATE = "templates/training-info-template.xlsx";

    public byte[] build(TrainingDtos.Detail detail) {
        List<List<Object>> rows = new ArrayList<>();
        for (TrainingDtos.Project project : detail.projects()) {
            List<Object> row = new ArrayList<>(57);
            for (TrainingDtos.FieldDefinition field : TrainingFieldSchema.fields()) {
                Object value = project.data().get(field.key());
                row.add(value == null ? "" : value);
            }
            rows.add(row);
        }
        try (InputStream template = new ClassPathResource(TEMPLATE).getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            try (ExcelWriter writer = EasyExcel.write(out).withTemplate(template).build()) {
                WriteSheet sheet = EasyExcel.writerSheet(0)
                        .needHead(false)
                        .useDefaultStyle(false)
                        .build();
                writer.write(rows, sheet);
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "培训信息 Excel 生成失败：" + e.getMessage());
        }
    }
}
