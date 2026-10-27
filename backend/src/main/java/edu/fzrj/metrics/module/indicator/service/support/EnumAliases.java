package edu.fzrj.metrics.module.indicator.service.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excel 导入导出的枚举别名表：同时接受枚举编码与中文名（导入），导出统一输出中文名。
 * 纯静态，便于单元测试。
 */
public final class EnumAliases {

    private EnumAliases() {
    }

    public static final Map<String, String> LEVEL = of(
            "A", "A", "A类", "A", "A_PLUS", "A_PLUS", "A+", "A_PLUS", "A+类", "A_PLUS");
    public static final Map<String, String> SUBJECT = of(
            "SCHOOL", "SCHOOL", "校级", "SCHOOL", "MAJOR_GROUP", "MAJOR_GROUP", "专业群", "MAJOR_GROUP");
    public static final Map<String, String> NATURE = of(
            "CONSTRAINT", "CONSTRAINT", "约束性", "CONSTRAINT", "DEVELOPMENT", "DEVELOPMENT", "发展性", "DEVELOPMENT");
    public static final Map<String, String> MEASURE_TYPE = of(
            "NUMBER", "NUMBER", "数值", "NUMBER",
            "BOOLEAN", "BOOLEAN", "是否", "BOOLEAN",
            "PERCENT_QUANTIFIABLE", "PERCENT_QUANTIFIABLE", "百分比-可量化", "PERCENT_QUANTIFIABLE",
            "PERCENT_DIRECT", "PERCENT_DIRECT", "百分比-不可量化", "PERCENT_DIRECT");
    public static final Map<String, String> STAT_CALIBER = of(
            "INCREMENT", "INCREMENT", "增量累计型", "INCREMENT",
            "STOCK", "STOCK", "存量时点型", "STOCK",
            "GROWTH", "GROWTH", "增长率型", "GROWTH",
            "RATIO", "RATIO", "占比型", "RATIO",
            "DIRECT_PERCENT", "DIRECT_PERCENT", "直填百分比型", "DIRECT_PERCENT",
            "BOOLEAN", "BOOLEAN", "是否型", "BOOLEAN");
    public static final Map<String, String> TARGET_DIRECTION = of(
            "HIGHER_BETTER", "HIGHER_BETTER", "越大越好", "HIGHER_BETTER",
            "LOWER_BETTER", "LOWER_BETTER", "越小越好", "LOWER_BETTER");
    public static final Map<String, String> ROLLUP_MODE = of(
            "AUTO_SUM", "AUTO_SUM", "自动汇总", "AUTO_SUM",
            "MANUAL", "MANUAL", "手工认定", "MANUAL");

    /** 编码 → 中文标签（导出用） */
    public static final Map<String, String> LABELS = of(
            "A", "A 类", "A_PLUS", "A+ 类",
            "SCHOOL", "校级", "MAJOR_GROUP", "专业群",
            "CONSTRAINT", "约束性", "DEVELOPMENT", "发展性",
            "NUMBER", "数值", "BOOLEAN", "是否",
            "PERCENT_QUANTIFIABLE", "百分比-可量化", "PERCENT_DIRECT", "百分比-不可量化",
            "INCREMENT", "增量累计型", "STOCK", "存量时点型", "GROWTH", "增长率型",
            "RATIO", "占比型", "DIRECT_PERCENT", "直填百分比型",
            "HIGHER_BETTER", "越大越好", "LOWER_BETTER", "越小越好",
            "AUTO_SUM", "自动汇总", "MANUAL", "手工认定");

    /** 按别名解析为枚举编码；raw 为 null / 空白 / 未识别时返回 null */
    public static String parse(Map<String, String> aliases, String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim();
        if (key.isEmpty()) {
            return null;
        }
        String code = aliases.get(key);
        return code == null ? aliases.get(key.toUpperCase()) : code;
    }

    /** 编码 → 中文标签（未识别返回原值） */
    public static String label(String code) {
        return code == null ? null : LABELS.getOrDefault(code, code);
    }

    /** 解析 是/否：是、Y、true、1 → true；否、N、false、0 → false；其余返回 null */
    public static Boolean parseYesNo(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase();
        return switch (key) {
            case "是", "y", "yes", "true", "1" -> Boolean.TRUE;
            case "否", "n", "no", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Map<String, String> of(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
