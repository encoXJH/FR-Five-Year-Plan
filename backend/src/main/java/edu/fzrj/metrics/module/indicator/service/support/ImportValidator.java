package edu.fzrj.metrics.module.indicator.service.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Excel 导入行校验（纯逻辑，40001 逐行错误明细）。
 * 只做行级与文件内一致性校验；A → A+ 守恒（40003）在定稿时强制，导入不拦截。
 */
public final class ImportValidator {

    private ImportValidator() {
    }

    /** 校验一行，返回错误明细（空列表 = 通过） */
    public static List<String> validate(ImportRowData row, ImportRowData.Context ctx) {
        List<String> errors = new ArrayList<>();
        String level = EnumAliases.parse(EnumAliases.LEVEL, row.level);
        if (level == null) {
            errors.add("层级必填（A / A+）");
            return errors;
        }
        if (!isBlank(row.code)) {
            row.code = row.code.trim();
            boolean formatOk = "A".equals(level)
                    ? IndicatorRules.isACode(row.code) : IndicatorRules.isAPlusCode(row.code);
            if (!formatOk) {
                errors.add("编号格式不正确（A 类为 A01 形式，A+ 为 A01-1 形式）");
            }
            if (ctx.existingCodes.contains(row.code)) {
                errors.add("编号已存在：" + row.code);
            }
            if (ctx.duplicatedCodesInFile.contains(row.code)) {
                errors.add("编号在文件内重复：" + row.code);
            }
        }
        boolean isA = "A".equals(level);
        if (!isA) {
            if (isBlank(row.parentCode)) {
                errors.add("A+ 必须填写上级编号");
            } else {
                row.parentCode = row.parentCode.trim();
                Boolean parentQuantifiable = ctx.aQuantifiableByCode.get(row.parentCode);
                if (parentQuantifiable == null) {
                    errors.add("上级编号不存在：" + row.parentCode);
                } else if (!parentQuantifiable) {
                    errors.add("上级 A 不可量化：" + row.parentCode);
                }
                if (!isBlank(row.code) && IndicatorRules.isAPlusCode(row.code)
                        && !row.code.startsWith(row.parentCode + "-")) {
                    errors.add("A+ 编号应以父指标编号开头");
                }
            }
        }
        if (isBlank(row.name)) {
            errors.add("关键产出指标必填");
        } else if (row.name.trim().length() > 200) {
            errors.add("关键产出指标不能超过 200 字");
        }
        if (EnumAliases.parse(EnumAliases.NATURE, row.nature) == null) {
            errors.add("指标性质必填（约束性 / 发展性）");
        }

        Boolean aQuantifiable = null;
        if (isA) {
            aQuantifiable = EnumAliases.parseYesNo(row.quantifiable);
            if (aQuantifiable == null) {
                errors.add("是否可量化必填（是 / 否）");
            }
        }
        boolean quantitative = !isA || !Boolean.FALSE.equals(aQuantifiable);
        if (!quantitative) {
            boolean hasQuantitativeInput = java.util.stream.Stream.of(row.measureType, row.statCaliber,
                            row.unitLabel, row.targetDirection, row.rollupMode, row.baseline, row.totalTarget)
                    .anyMatch(value -> !isBlank(value));
            boolean hasYearTargets = row.yearTargets.values().stream().anyMatch(value -> !isBlank(value));
            if (hasQuantitativeInput || hasYearTargets) {
                errors.add("不可量化 A 的量化字段和年度目标必须留空");
            }
        } else {
            String measureType = EnumAliases.parse(EnumAliases.MEASURE_TYPE, row.measureType);
            String statCaliber = EnumAliases.parse(EnumAliases.STAT_CALIBER, row.statCaliber);
            if (measureType == null) {
                errors.add("计量类型必填（数值 / 是否 / 百分比-可量化 / 百分比-不可量化）");
            }
            if (statCaliber == null) {
                errors.add("统计口径必填（增量累计型 / 存量时点型 / 增长率型 / 占比型 / 直填百分比型 / 是否型）");
            }
            if (measureType != null && statCaliber != null
                    && !IndicatorRules.isValidCombination(measureType, statCaliber)) {
                errors.add("计量类型与统计口径不匹配");
            }
            if (isBlank(row.unitLabel)) {
                errors.add("单位必填");
            }
            if (!isBlank(row.targetDirection)
                    && EnumAliases.parse(EnumAliases.TARGET_DIRECTION, row.targetDirection) == null) {
                errors.add("达成方向取值不合法（越大越好 / 越小越好）");
            }
            if (isA && EnumAliases.parse(EnumAliases.ROLLUP_MODE, row.rollupMode) == null) {
                errors.add("可量化的 A 必须填写汇总方式（自动汇总 / 手工认定）");
            }
            if (("GROWTH".equals(statCaliber) || "STOCK".equals(statCaliber))
                    && parseDecimal(row.baseline) == null) {
                errors.add("增长率型 / 存量时点型必须填写数值型基线值");
            } else if (!isBlank(row.baseline) && parseDecimal(row.baseline) == null) {
                errors.add("基线值应为数字");
            }

            BigDecimal totalTarget = parseDecimal(row.totalTarget);
            if (!"BOOLEAN".equals(statCaliber) && totalTarget == null) {
                errors.add("五年目标必填且应为数字");
            }
            Map<Integer, BigDecimal> targets = new java.util.LinkedHashMap<>();
            for (int year = ctx.startYear; year <= ctx.endYear; year++) {
                String raw = row.yearTargets.get(year);
                if ("BOOLEAN".equals(statCaliber)) {
                    Boolean marked = EnumAliases.parseYesNo(raw);
                    if (!isBlank(raw) && marked == null) {
                        errors.add(year + " 年目标应填「是」或留空（是否型）");
                    }
                    targets.put(year, Boolean.TRUE.equals(marked) ? BigDecimal.ONE : null);
                } else {
                    BigDecimal value = parseDecimal(raw);
                    if (value == null) {
                        errors.add(year + " 年目标必填且应为数字");
                    }
                    targets.put(year, value);
                }
            }
            if ("BOOLEAN".equals(statCaliber)) {
                long marked = targets.values().stream().filter(java.util.Objects::nonNull).count();
                if (marked != 1) {
                    errors.add("是否型指标必须且只能指定一个计划完成年份（填「是」）");
                }
            } else if ("INCREMENT".equals(statCaliber) && totalTarget != null
                    && targets.values().stream().allMatch(java.util.Objects::nonNull)) {
                BigDecimal sum = targets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                if (sum.compareTo(totalTarget) != 0) {
                    errors.add("各年度目标之和不等于五年总量");
                }
            }
        }
        if (isBlank(row.ownerUsername)) {
            if (isA) {
                errors.add("负责人账号必填");
            }
        } else if (!ctx.validUsernames.contains(row.ownerUsername.trim())) {
            errors.add("负责人账号不存在：" + row.ownerUsername.trim());
        }
        return errors;
    }

    /** 年份列表头与计划起止一致性校验（表头级），不一致返回错误信息，否则 null */
    public static String checkYearColumns(List<Integer> yearColumns, int startYear, int endYear) {
        List<Integer> expected = new ArrayList<>();
        for (int y = startYear; y <= endYear; y++) {
            expected.add(y);
        }
        List<Integer> actual = yearColumns.stream().sorted().toList();
        if (!actual.equals(expected)) {
            return "年份列与计划起止年份不一致（应包含 " + startYear + "-" + endYear + " 每一年的「XXXX年目标」列）";
        }
        return null;
    }

    /** 解析十进制数；空白返回 null，非数字返回 null（调用方按需区分） */
    public static BigDecimal parseDecimal(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
