package edu.fzrj.metrics.module.training.service;

import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingFieldSchemaTest {

    @Test
    void 字段固定57列并分为七组() {
        List<TrainingDtos.FieldDefinition> fields = TrainingFieldSchema.fields();

        assertEquals(57, fields.size());
        for (int i = 0; i < fields.size(); i++) {
            assertEquals(i + 1, fields.get(i).column());
            assertEquals("c%02d".formatted(i + 1), fields.get(i).key());
        }
        assertEquals(List.of("项目基本", "合同经费", "培训实施", "培训对象", "重点行业", "区域战略", "教师填报"),
                fields.stream().map(TrainingDtos.FieldDefinition::groupLabel).distinct().toList());
    }

    @Test
    void 完整项目自动计算合计经费及填报人() {
        TrainingFieldSchema.ValidationResult result = TrainingFieldSchema.validate(fullData(), "田明月", true, "data");

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(6, result.data().get("c32"));
        assertEquals(6, result.data().get("c44"));
        assertEquals(6, result.data().get("c54"));
        assertEquals("田明月", result.data().get("c57"));
        BigDecimal funding = ((BigDecimal) result.data().get("c10")).add((BigDecimal) result.data().get("c11"));
        assertEquals(0, new BigDecimal("10").compareTo(funding));
    }

    @Test
    void 草稿允许缺项但提交必须完整且总人数等于人群合计() {
        Map<String, Object> draft = TrainingFieldSchema.validate(Map.of(), "田明月", false, "data").data();
        assertEquals(0, draft.get("c32"));

        Map<String, Object> input = fullData();
        input.put("c14", 5);
        input.remove("c01");
        TrainingFieldSchema.ValidationResult result = TrainingFieldSchema.validate(input, "田明月", true, "data");

        assertTrue(result.errors().stream().anyMatch(error -> error.getField().equals("data.c01")));
        assertTrue(result.errors().stream().anyMatch(error -> error.getField().equals("data.c14")));
    }

    @Test
    void 拒绝错误日期负数非法选项和未知字段() {
        Map<String, Object> input = fullData();
        input.put("c06", "2025-08-01");
        input.put("c09", -1);
        input.put("c17", "未知方式");
        input.put("other", 1);

        TrainingFieldSchema.ValidationResult result = TrainingFieldSchema.validate(input, "田明月", true, "data");

        assertTrue(result.errors().stream().anyMatch(error -> error.getField().equals("data.c06")));
        assertTrue(result.errors().stream().anyMatch(error -> error.getField().equals("data.c09")));
        assertTrue(result.errors().stream().anyMatch(error -> error.getField().equals("data.c17")));
        assertTrue(result.errors().stream().anyMatch(error -> error.getField().equals("data.other")));
        assertFalse(result.errors().isEmpty());
    }

    static Map<String, Object> fullData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("c01", "人工智能应用培训");
        data.put("c02", "是");
        data.put("c03", "否");
        data.put("c04", "否");
        data.put("c05", "2025-09-01");
        data.put("c06", "2025-09-02");
        data.put("c07", "企业");
        data.put("c08", "某科技企业");
        data.put("c09", new BigDecimal("10"));
        data.put("c10", new BigDecimal("6"));
        data.put("c11", new BigDecimal("4"));
        data.put("c12", 8);
        data.put("c13", "新业态新技术类");
        data.put("c14", 6);
        data.put("c15", "人工智能");
        data.put("c16", 2);
        data.put("c17", "线下");
        for (int column = 18; column <= 31; column++) data.put("c%02d".formatted(column), 0);
        data.put("c18", 1);
        data.put("c19", 2);
        data.put("c20", 3);
        for (int column = 33; column <= 43; column++) data.put("c%02d".formatted(column), 0);
        data.put("c33", 2);
        data.put("c34", 1);
        data.put("c40", 3);
        for (int column = 45; column <= 53; column++) data.put("c%02d".formatted(column), 0);
        data.put("c45", 4);
        data.put("c46", 2);
        data.put("c55", "张老师");
        data.put("c56", "企业导师");
        return data;
    }
}
