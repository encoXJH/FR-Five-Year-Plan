package edu.fzrj.metrics.module.indicator.service.support;

import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 指标纯逻辑规则（PRD 4.4 / 4.5，API 约定第 9 章）：
 * 计量类型×统计口径组合、编号格式、守恒校验、BOOLEAN 计划完成年等。
 * 不依赖 Spring，便于单元测试。
 */
public final class IndicatorRules {

    private IndicatorRules() {
    }

    public static final String LEVEL_A = "A";
    public static final String LEVEL_A_PLUS = "A_PLUS";

    private static final Pattern A_CODE = Pattern.compile("A\\d{2}");
    private static final Pattern A_PLUS_CODE = Pattern.compile("A\\d{2}-\\d+");

    /** 计量类型 → 允许的统计口径（不符合返回 40007） */
    private static final Map<String, List<String>> MEASURE_CALIBERS = Map.of(
            "NUMBER", List.of("INCREMENT", "STOCK"),
            "BOOLEAN", List.of("BOOLEAN"),
            "PERCENT_QUANTIFIABLE", List.of("GROWTH", "RATIO"),
            "PERCENT_DIRECT", List.of("DIRECT_PERCENT"));

    /** 比率类口径：目标不做求和校验（PRD 4.5 注） */
    private static final Collection<String> RATIO_CALIBERS = List.of("GROWTH", "RATIO", "DIRECT_PERCENT");

    public static boolean isValidCombination(String measureType, String statCaliber) {
        return MEASURE_CALIBERS.getOrDefault(measureType, List.of()).contains(statCaliber);
    }

    /** 组合不合法抛 40007 */
    public static void requireValidCombination(String measureType, String statCaliber) {
        if (!isValidCombination(measureType, statCaliber)) {
            throw new BizException(ErrorCode.CALIBER_MISMATCH);
        }
    }

    public static boolean isRatioCaliber(String statCaliber) {
        return RATIO_CALIBERS.contains(statCaliber);
    }

    /** 目标需要求和校验的口径（目前只有 INCREMENT 强制 Σ年=五年总量） */
    public static boolean requiresYearSum(String statCaliber) {
        return "INCREMENT".equals(statCaliber);
    }

    public static boolean isACode(String code) {
        return code != null && A_CODE.matcher(code).matches();
    }

    public static boolean isAPlusCode(String code) {
        return code != null && A_PLUS_CODE.matcher(code).matches();
    }

    /** 校验编号格式与层级匹配，不合法抛 10001 */
    public static void requireCodeFormat(String level, String code) {
        boolean ok = LEVEL_A.equals(level) ? isACode(code) : isAPlusCode(code);
        if (!ok) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    LEVEL_A.equals(level) ? "A 类编号格式应为 A01 形式" : "A+ 类编号格式应为 A01-1 形式");
        }
    }

    /** 生成下一个 A 编号：A01、A02 …（在既有编号集合上取最大 +1） */
    public static String nextACode(Collection<String> existingCodes) {
        int max = 0;
        for (String code : existingCodes) {
            if (isACode(code)) {
                max = Math.max(max, Integer.parseInt(code.substring(1)));
            }
        }
        return String.format("A%02d", max + 1);
    }

    /** 生成下一个 A+ 编号：{父编号}-1、-2 … */
    public static String nextAPlusCode(String parentCode, Collection<String> existingCodes) {
        int max = 0;
        String prefix = parentCode + "-";
        for (String code : existingCodes) {
            if (code != null && code.startsWith(prefix)) {
                String suffix = code.substring(prefix.length());
                if (suffix.matches("\\d+")) {
                    max = Math.max(max, Integer.parseInt(suffix));
                }
            }
        }
        return prefix + (max + 1);
    }

    /** A+ 本身即为量化指标；A 由 isQuantifiable 决定 */
    public static boolean isQuantifiable(String level, Boolean quantifiable) {
        return LEVEL_A_PLUS.equals(level) || (LEVEL_A.equals(level) && Boolean.TRUE.equals(quantifiable));
    }

    /** A+ 挂载校验：父必须是可量化的 A，否则 40006 */
    public static void requireAPlusAttachable(String parentLevel, Boolean parentQuantifiable) {
        if (!LEVEL_A.equals(parentLevel) || !Boolean.TRUE.equals(parentQuantifiable)) {
            throw new BizException(ErrorCode.LEVEL_INVALID, "A+ 必须挂在可量化的 A 下面");
        }
    }

    /** 量化指标必填定义；不可量化 A 跳过全部量化校验 */
    public static void requireQuantitativeDefinition(String level, Boolean quantifiable,
                                                     String measureType, String statCaliber,
                                                     String unitLabel, String targetDirection,
                                                     String rollupMode) {
        if (!isQuantifiable(level, quantifiable)) {
            return;
        }
        if (measureType == null || measureType.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "计量类型必填");
        }
        if (statCaliber == null || statCaliber.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "统计口径必填");
        }
        if (unitLabel == null || unitLabel.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "计量单位必填");
        }
        if (targetDirection == null || targetDirection.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "达成方向必填");
        }
        if (LEVEL_A.equals(level) && (rollupMode == null || rollupMode.isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "可量化的 A 必须指定汇总方式");
        }
        requireValidCombination(measureType, statCaliber);
    }

    /** 不可量化 A 不设置年度目标 */
    public static void requireYearTargetsAllowed(String level, Boolean quantifiable) {
        if (!isQuantifiable(level, quantifiable)) {
            throw new BizException(ErrorCode.LEVEL_INVALID, "不可量化 A 不设置量化字段和年度目标");
        }
    }

    /**
     * INCREMENT 守恒：各年度目标之和 = 五年总量（BigDecimal 按值比较，不等抛 40002）。
     * 比率类口径（GROWTH / RATIO / DIRECT_PERCENT）与 STOCK、BOOLEAN 不校验。
     */
    public static void requireIncrementSum(String statCaliber, BigDecimal totalTarget,
                                           Collection<BigDecimal> yearTargets) {
        if (!requiresYearSum(statCaliber)) {
            return;
        }
        if (totalTarget == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "五年目标总量必填");
        }
        BigDecimal sum = yearTargets.stream()
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(totalTarget) != 0) {
            throw new BizException(ErrorCode.YEAR_TARGETS_SUM_INVALID,
                    "各年度目标之和 " + sum.stripTrailingZeros().toPlainString()
                            + " 不等于五年总量 " + totalTarget.stripTrailingZeros().toPlainString());
        }
    }

    /**
     * A → A+ 拆分守恒（汇总方式 AUTO_SUM 且非比率类口径时）：Σ A+ 五年总量 = A 五年总量，不等抛 40003。
     */
    public static void requireAutoSumConservation(String statCaliber, String rollupMode,
                                                  BigDecimal aTotal, Collection<BigDecimal> aPlusTotals) {
        if (!"AUTO_SUM".equals(rollupMode) || isRatioCaliber(statCaliber)) {
            return;
        }
        if (aTotal == null) {
            throw new BizException(ErrorCode.A_PLUS_SUM_INVALID, "自动汇总的 A 类指标必须设置五年目标总量");
        }
        BigDecimal sum = aPlusTotals.stream()
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(aTotal) != 0) {
            throw new BizException(ErrorCode.A_PLUS_SUM_INVALID,
                    "A+ 目标之和 " + sum.stripTrailingZeros().toPlainString()
                            + " 不等于 A 目标 " + aTotal.stripTrailingZeros().toPlainString());
        }
    }

    /** BOOLEAN 口径：计划完成年唯一（恰好一年为 1，其余为 null），违反抛 10001 */
    public static void requireBooleanSingleYear(Map<Integer, BigDecimal> yearTargets) {
        long count = yearTargets.values().stream()
                .filter(v -> v != null && v.compareTo(BigDecimal.ONE) == 0)
                .count();
        if (count != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "是否型指标必须且只能指定一个计划完成年份");
        }
    }

    /**
     * 规范化前端传入的年度目标值：
     * BOOLEAN 口径接受 true / null（转 1 / NULL 存储）；数值口径接受数字（转 BigDecimal）。
     */
    public static BigDecimal normalizeTarget(Object raw, String statCaliber) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b) {
            if (!"BOOLEAN".equals(statCaliber)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "目标值应为数字");
            }
            return b ? BigDecimal.ONE : null;
        }
        if (raw instanceof Number n) {
            if ("BOOLEAN".equals(statCaliber)) {
                BigDecimal v = new BigDecimal(n.toString());
                if (v.compareTo(BigDecimal.ONE) != 0 && v.compareTo(BigDecimal.ZERO) != 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "是否型目标值只能为 true");
                }
                return v.compareTo(BigDecimal.ONE) == 0 ? BigDecimal.ONE : null;
            }
            return new BigDecimal(n.toString());
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "目标值格式不正确");
    }

    /** 年度集合必须恰好等于计划起止年（每年一次），违反抛 10001 */
    public static void requirePlanYears(Collection<Integer> years, int startYear, int endYear) {
        List<Integer> expected = new java.util.ArrayList<>();
        for (int y = startYear; y <= endYear; y++) {
            expected.add(y);
        }
        List<Integer> actual = years.stream().sorted().toList();
        if (!actual.equals(expected)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "年度目标必须覆盖 " + startYear + "-" + endYear + " 每一年且不重复");
        }
    }

    /** 增长率：今年总量 ÷ 去年总量 − 1；首年的“去年总量”即基线值（PRD 4.4.3） */
    public static BigDecimal growthRate(BigDecimal currentTotal, BigDecimal previousTotal) {
        if (previousTotal == null || previousTotal.compareTo(BigDecimal.ZERO) == 0 || currentTotal == null) {
            return null;
        }
        return currentTotal.divide(previousTotal, 6, java.math.RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
    }

    /** 取某年增长率计算所需的上年总量：首年（计划起始年）取基线值 */
    public static BigDecimal previousTotalForGrowth(Map<Integer, BigDecimal> totalsByYear,
                                                    int year, int startYear, BigDecimal baselineValue) {
        if (year == startYear) {
            return baselineValue;
        }
        return totalsByYear.get(year - 1);
    }
}
