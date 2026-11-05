package edu.fzrj.metrics.module.indicator.service.support;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportValidatorTest {

    private ImportRowData.Context ctx() {
        ImportRowData.Context ctx = new ImportRowData.Context(2026, 2030);
        ctx.existingCodes = Set.of("A09");
        ctx.duplicatedCodesInFile = Set.of();
        Map<String, Boolean> aQuantifiable = new HashMap<>();
        aQuantifiable.put("A16", true);
        aQuantifiable.put("A08", false);
        ctx.aQuantifiableByCode = aQuantifiable;
        ctx.validUsernames = Set.of("owner1", "owner2");
        return ctx;
    }

    private ImportRowData validARow() {
        ImportRowData row = new ImportRowData();
        row.rowNum = 2;
        row.level = "A";
        row.code = "A16";
        row.name = "整校订单式人才培养人数。订单式人才培养包含订单班、冠名班、现代学徒制、现场工程师、企业新型学徒制、产业学院、工匠班、校企合作工作室、校企微专业等培养方式。逐年增长";
        row.nature = "发展性";
        row.measureType = "数值";
        row.statCaliber = "增量累计型";
        row.unitLabel = "人";
        row.targetDirection = "越大越好";
        row.rollupMode = "自动汇总";
        row.quantifiable = "是";
        row.totalTarget = "16290";
        row.ownerUsername = "owner1";
        row.yearTargets = years("2458", "2858", "3258", "3658", "4058");
        return row;
    }

    private Map<Integer, String> years(String... values) {
        Map<Integer, String> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                map.put(2026 + i, values[i]);
            }
        }
        return map;
    }

    @Test
    void 合法A行校验通过() {
        assertTrue(ImportValidator.validate(validARow(), ctx()).isEmpty());
    }

    @Test
    void 编号格式不正确() {
        ImportRowData row = validARow();
        row.code = "01";
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "编号格式"));
        row.code = "A1";
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "编号格式"));
    }

    @Test
    void 编号与库内重复() {
        ImportRowData row = validARow();
        row.code = "A09";
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "编号已存在"));
    }

    @Test
    void 编号文件内重复() {
        ImportRowData.Context ctx = ctx();
        ctx.duplicatedCodesInFile = Set.of("A16");
        assertTrue(hasError(ImportValidator.validate(validARow(), ctx), "文件内重复"));
    }

    @Test
    void A加上级编号必填且必须存在() {
        ImportRowData row = validAPlusRow();
        row.parentCode = null;
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "上级编号"));
        row.parentCode = "A99";
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "上级编号不存在"));
    }

    @Test
    void A加挂到不可量化A报错() {
        ImportRowData row = validAPlusRow();
        row.parentCode = "A08";
        row.code = null; // 避免编号前缀干扰
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "不可量化"));
    }

    @Test
    void A加编号须以父编号开头() {
        ImportRowData row = validAPlusRow();
        row.code = "A02-1";
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "父指标编号开头"));
    }

    @Test
    void 计量类型与统计口径不匹配() {
        ImportRowData row = validARow();
        row.measureType = "数值";
        row.statCaliber = "占比型";
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "不匹配"));
    }

    @Test
    void 可量化A缺汇总方式() {
        ImportRowData row = validARow();
        row.rollupMode = null;
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "汇总方式"));
    }

    @Test
    void 不可量化A仅填写基础信息合法() {
        assertTrue(ImportValidator.validate(nonQuantifiableARow(), ctx()).isEmpty());
    }

    @Test
    void 不可量化A不得填写量化字段或年度目标() {
        ImportRowData row = nonQuantifiableARow();
        row.totalTarget = "5";
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "量化字段和年度目标必须留空"));

        row = nonQuantifiableARow();
        row.yearTargets = years(null, null, "1", null, null);
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "量化字段和年度目标必须留空"));
    }

    @Test
    void 增长率型必须填基线值() {
        ImportRowData row = validARow();
        row.measureType = "百分比-可量化";
        row.statCaliber = "增长率型";
        row.totalTarget = "115";
        row.baseline = null;
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "基线值"));
        row.baseline = "100";
        assertTrue(ImportValidator.validate(row, ctx()).isEmpty());
    }

    @Test
    void INCREMENT年和不等于五年总量() {
        ImportRowData row = validARow();
        row.yearTargets = years("1", "1", "1", "1", "2");
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "之和不等于五年总量"));
    }

    @Test
    void 占比型目标不做求和校验() {
        ImportRowData row = validARow();
        row.measureType = "百分比-可量化";
        row.statCaliber = "占比型";
        row.totalTarget = "0.6";
        row.yearTargets = years("0.5", "0.55", "0.6", "0.6", "0.6");
        assertTrue(ImportValidator.validate(row, ctx()).isEmpty());
    }

    @Test
    void 是否型完成年必须唯一() {
        ImportRowData row = validARow();
        row.measureType = "是否";
        row.statCaliber = "是否型";
        row.totalTarget = null;
        row.rollupMode = "自动汇总";
        row.quantifiable = "是";
        row.yearTargets = years("是", "是", null, null, null);
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "只能指定一个计划完成年份"));
        row.yearTargets = years(null, null, "是", null, null);
        assertTrue(ImportValidator.validate(row, ctx()).isEmpty());
    }

    @Test
    void 非是否型年度目标必填() {
        ImportRowData row = validARow();
        row.yearTargets = years("1", "1", "1", "1", null);
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "2030 年目标必填"));
    }

    @Test
    void A负责人必填且必须存在() {
        ImportRowData row = validARow();
        row.ownerUsername = "ghost";
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "负责人账号不存在"));
        row.ownerUsername = null;
        assertTrue(hasError(ImportValidator.validate(row, ctx()), "负责人账号必填"));
    }

    @Test
    void A加负责人留空合法() {
        ImportRowData row = validAPlusRow();
        row.ownerUsername = null;
        assertTrue(ImportValidator.validate(row, ctx()).isEmpty());
    }

    @Test
    void 年份列与计划起止一致性() {
        assertNull(ImportValidator.checkYearColumns(List.of(2030, 2026, 2027, 2028, 2029), 2026, 2030));
        assertNotNull(ImportValidator.checkYearColumns(List.of(2026, 2027, 2028, 2029), 2026, 2030));
        assertNotNull(ImportValidator.checkYearColumns(List.of(2025, 2026, 2027, 2028, 2029, 2030), 2026, 2030));
    }

    private ImportRowData nonQuantifiableARow() {
        ImportRowData row = validARow();
        row.quantifiable = "否";
        row.measureType = null;
        row.statCaliber = null;
        row.unitLabel = null;
        row.targetDirection = null;
        row.rollupMode = null;
        row.baseline = null;
        row.totalTarget = null;
        row.yearTargets = years(null, null, null, null, null);
        return row;
    }

    private ImportRowData validAPlusRow() {
        ImportRowData row = new ImportRowData();
        row.rowNum = 3;
        row.level = "A+";
        row.code = "A16-1";
        row.parentCode = "A16";
        row.name = "整校订单式人才培养人数。订单式人才培养包含订单班、冠名班、现代学徒制、现场工程师、企业新型学徒制、产业学院、工匠班、校企合作工作室、校企微专业等培养方式。逐年增长";
        row.nature = "发展性";
        row.measureType = "数值";
        row.statCaliber = "增量累计型";
        row.unitLabel = "人";
        row.targetDirection = "越大越好";
        row.totalTarget = "16290";
        row.ownerUsername = "owner1";
        row.yearTargets = years("2458", "2858", "3258", "3658", "4058");
        return row;
    }

    private boolean hasError(List<String> errors, String keyword) {
        return errors.stream().anyMatch(e -> e.contains(keyword));
    }

    @Test
    void 比率类整数百分数换算为小数() {
        assertEquals(0, new java.math.BigDecimal("0.70")
                .compareTo(ImportValidator.normalizePercent(new java.math.BigDecimal("70"), "DIRECT_PERCENT")));
        assertEquals(0, new java.math.BigDecimal("0.13")
                .compareTo(ImportValidator.normalizePercent(new java.math.BigDecimal("13"), "RATIO")));
        assertEquals(0, new java.math.BigDecimal("0.7")
                .compareTo(ImportValidator.normalizePercent(new java.math.BigDecimal("0.7"), "DIRECT_PERCENT")));
        assertEquals(0, new java.math.BigDecimal("70")
                .compareTo(ImportValidator.normalizePercent(new java.math.BigDecimal("70"), "INCREMENT")));
        assertNull(ImportValidator.normalizePercent(null, "DIRECT_PERCENT"));
    }

    @Test
    void 枚举别名_编码与中文均可解析() {
        assertEquals("INCREMENT", EnumAliases.parse(EnumAliases.STAT_CALIBER, "增量累计型"));
        assertEquals("INCREMENT", EnumAliases.parse(EnumAliases.STAT_CALIBER, "INCREMENT"));
        assertEquals("A_PLUS", EnumAliases.parse(EnumAliases.LEVEL, "A+"));
        assertEquals("MAJOR_GROUP", EnumAliases.parse(EnumAliases.SUBJECT, "专业群"));
        assertNull(EnumAliases.parse(EnumAliases.NATURE, "不存在的值"));
        assertEquals(Boolean.TRUE, EnumAliases.parseYesNo("是"));
        assertEquals(Boolean.FALSE, EnumAliases.parseYesNo("否"));
        assertNull(EnumAliases.parseYesNo("maybe"));
        assertEquals("约束性", EnumAliases.label("CONSTRAINT"));
        assertEquals("原样", EnumAliases.label("原样"));
    }
}
