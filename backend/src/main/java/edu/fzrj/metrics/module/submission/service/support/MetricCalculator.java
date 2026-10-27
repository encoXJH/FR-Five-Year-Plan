package edu.fzrj.metrics.module.submission.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * 完成值计算（纯逻辑，PRD 4.4 / 4.5；所有数值用 BigDecimal）：
 * <ul>
 *   <li>INCREMENT 增量累计型：年度完成值 = 当年审核通过的增量之和；</li>
 *   <li>STOCK 存量时点型：年度完成值 = 当年最后一次审核通过的总量；增量 = 本年总量 − 上年总量；</li>
 *   <li>GROWTH 增长率型：只采集总量；年度增长率 = 今年总量 ÷ 去年总量 − 1（第一年用基线值）；</li>
 *   <li>RATIO 占比型：Σ分子 ÷ Σ分母（不能平均百分比）；</li>
 *   <li>DIRECT_PERCENT 直填百分比型：当年最后一次审核通过的百分比；</li>
 *   <li>BOOLEAN 是否型：任一审核通过为完成即完成。</li>
 * </ul>
 * B → A+ → A 逐级汇总复用同一套口径规则（rollup）。
 */
public final class MetricCalculator {

    /** 计算结果保留 6 位小数（与表 DECIMAL(20,6) 对齐） */
    private static final int SCALE = 6;

    private MetricCalculator() {
    }

    /**
     * 单条审核通过的有效值（认定值优先；调用方已按口径填充对应字段，其余为 null）。
     */
    public record ApprovedValue(BigDecimal amount, BigDecimal total, BigDecimal numerator,
                                BigDecimal denominator, BigDecimal percent, Boolean achieved) {

        public static ApprovedValue increment(BigDecimal amount) {
            return new ApprovedValue(amount, null, null, null, null, null);
        }

        public static ApprovedValue total(BigDecimal total) {
            return new ApprovedValue(null, total, null, null, null, null);
        }

        public static ApprovedValue ratio(BigDecimal numerator, BigDecimal denominator) {
            return new ApprovedValue(null, null, numerator, denominator, null, null);
        }

        public static ApprovedValue percent(BigDecimal percent) {
            return new ApprovedValue(null, null, null, null, percent, null);
        }

        public static ApprovedValue bool(Boolean achieved) {
            return new ApprovedValue(null, null, null, null, null, achieved);
        }
    }

    /**
     * 年度完成值结果：actualValue 为口径主值（STOCK / GROWTH 口径主值见各方法注释），
     * numerator / denominator 仅 RATIO 使用，total 仅 STOCK / GROWTH 使用，
     * growthRate 仅 GROWTH 使用，increment 仅 STOCK 使用。
     */
    public record YearResult(BigDecimal actualValue, BigDecimal numerator, BigDecimal denominator,
                             BigDecimal total, BigDecimal growthRate, BigDecimal increment) {

        public static YearResult empty() {
            return new YearResult(null, null, null, null, null, null);
        }

        public boolean hasData() {
            return actualValue != null || total != null || numerator != null;
        }
    }

    /**
     * B 单任务年度完成值。
     *
     * @param caliber   统计口径
     * @param values    当年审核通过的有效值，按审核通过时间升序（STOCK / GROWTH / DIRECT_PERCENT 取最后一条）
     * @param prevTotal 上年完成总量（STOCK / GROWTH 口径；第一年传基线值；无上年数据传 null）
     */
    public static YearResult yearActual(String caliber, List<ApprovedValue> values, BigDecimal prevTotal) {
        List<ApprovedValue> safe = values == null ? List.of() : values.stream()
                .filter(Objects::nonNull).toList();
        if (safe.isEmpty()) {
            return YearResult.empty();
        }
        return switch (caliber) {
            case "INCREMENT" -> {
                BigDecimal sum = safe.stream().map(ApprovedValue::amount)
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                yield new YearResult(sum, null, null, null, null, null);
            }
            case "STOCK" -> {
                BigDecimal total = lastTotal(safe);
                BigDecimal increment = (total == null || prevTotal == null) ? null : total.subtract(prevTotal);
                yield new YearResult(total, null, null, total, null, increment);
            }
            case "GROWTH" -> {
                BigDecimal total = lastTotal(safe);
                yield new YearResult(growthRate(total, prevTotal), null, null, total,
                        growthRate(total, prevTotal), null);
            }
            case "RATIO" -> {
                BigDecimal numerator = safe.stream().map(ApprovedValue::numerator)
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal denominator = safe.stream().map(ApprovedValue::denominator)
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                yield new YearResult(rate(numerator, denominator), numerator, denominator, null, null, null);
            }
            case "DIRECT_PERCENT" -> new YearResult(lastPercent(safe), null, null, null, null, null);
            case "BOOLEAN" -> {
                boolean anyTrue = safe.stream().anyMatch(v -> Boolean.TRUE.equals(v.achieved()));
                yield new YearResult(anyTrue ? BigDecimal.ONE : BigDecimal.ZERO, null, null, null, null, null);
            }
            default -> throw new IllegalArgumentException("未知统计口径：" + caliber);
        };
    }

    /**
     * 下级完成值向上汇总（B → A+ → A 共用）。
     *
     * @param caliber   统计口径
     * @param children  下级年度完成值（task_year / indicator_year_target 缓存换算成的 YearResult）
     * @param prevTotal 本级上年完成总量（STOCK / GROWTH 口径；第一年传基线值；无传 null）
     */
    public static YearResult rollup(String caliber, List<YearResult> children, BigDecimal prevTotal) {
        List<YearResult> safe = children == null ? List.of() : children.stream()
                .filter(Objects::nonNull).toList();
        boolean anyData = safe.stream().anyMatch(YearResult::hasData);
        if (!anyData) {
            return YearResult.empty();
        }
        return switch (caliber) {
            case "INCREMENT" -> {
                BigDecimal sum = sumOrNull(safe.stream().map(YearResult::actualValue).toList());
                yield new YearResult(sum, null, null, null, null, null);
            }
            case "STOCK" -> {
                BigDecimal total = sumOrNull(safe.stream().map(YearResult::total).toList());
                BigDecimal increment = (total == null || prevTotal == null) ? null : total.subtract(prevTotal);
                yield new YearResult(total, null, null, total, null, increment);
            }
            case "GROWTH" -> {
                BigDecimal total = sumOrNull(safe.stream().map(YearResult::total).toList());
                yield new YearResult(growthRate(total, prevTotal), null, null, total,
                        growthRate(total, prevTotal), null);
            }
            case "RATIO" -> {
                BigDecimal numerator = sumOrNull(safe.stream().map(YearResult::numerator).toList());
                BigDecimal denominator = sumOrNull(safe.stream().map(YearResult::denominator).toList());
                yield new YearResult(rate(numerator, denominator), numerator, denominator, null, null, null);
            }
            case "DIRECT_PERCENT" -> {
                // 占比不可相加（PRD 4.4.4）：只有一个下级有数据时沿用其值，多个下级有数据时不自动汇总（待手工认定）
                List<BigDecimal> percents = safe.stream().map(YearResult::actualValue)
                        .filter(Objects::nonNull).toList();
                yield new YearResult(percents.size() == 1 ? percents.get(0) : null, null, null, null, null, null);
            }
            case "BOOLEAN" -> {
                List<BigDecimal> values = safe.stream().map(YearResult::actualValue)
                        .filter(Objects::nonNull).toList();
                if (values.isEmpty()) {
                    yield YearResult.empty();
                }
                boolean allAchieved = values.stream().allMatch(v -> BigDecimal.ONE.compareTo(v) == 0);
                yield new YearResult(allAchieved ? BigDecimal.ONE : BigDecimal.ZERO, null, null, null, null, null);
            }
            default -> throw new IllegalArgumentException("未知统计口径：" + caliber);
        };
    }

    /** 完成率（小数）：目标为空或 0 时返回 null（BOOLEAN 目标恒为 1，非完成年 target 为 null 返回 null） */
    public static BigDecimal completionRate(String caliber, YearResult result, BigDecimal target) {
        if (result == null || target == null) {
            return null;
        }
        if ("BOOLEAN".equals(caliber)) {
            if (result.actualValue() == null) {
                return null;
            }
            return BigDecimal.ONE.compareTo(target) == 0 ? result.actualValue() : null;
        }
        if (BigDecimal.ZERO.compareTo(target) == 0) {
            return null;
        }
        BigDecimal value = "STOCK".equals(caliber) || "GROWTH".equals(caliber)
                ? result.total() : result.actualValue();
        if (value == null) {
            return null;
        }
        return value.divide(target, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 达标判定：越大越好 ≥ 目标；越小越好 ≤ 目标（PRD 4.4.6）。
     * 无数据或目标为空返回 null（无法判定）；GROWTH / STOCK 用总量比目标总量。
     */
    public static Boolean achieved(String direction, String caliber, YearResult result, BigDecimal target) {
        if (result == null || target == null) {
            return null;
        }
        BigDecimal value = "STOCK".equals(caliber) || "GROWTH".equals(caliber)
                ? result.total() : result.actualValue();
        if (value == null) {
            return null;
        }
        int cmp = value.compareTo(target);
        return "LOWER_BETTER".equals(direction) ? cmp <= 0 : cmp >= 0;
    }

    /** 达成状态缓存：无数据 NOT_STARTED；达标（完成率 &gt; 100% 为 EXCEEDED）；否则 IN_PROGRESS */
    public static String achieveStatus(String direction, String caliber, YearResult result, BigDecimal target) {
        if (result == null || !result.hasData()) {
            return "NOT_STARTED";
        }
        Boolean ok = achieved(direction, caliber, result, target);
        if (ok == null) {
            return "IN_PROGRESS";
        }
        if (!ok) {
            return "IN_PROGRESS";
        }
        BigDecimal rate = completionRate(caliber, result, target);
        return rate != null && rate.compareTo(BigDecimal.ONE) > 0 ? "EXCEEDED" : "ACHIEVED";
    }

    // ---------------- 内部 ----------------

    private static BigDecimal lastTotal(List<ApprovedValue> values) {
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i).total() != null) {
                return values.get(i).total();
            }
        }
        return null;
    }

    private static BigDecimal lastPercent(List<ApprovedValue> values) {
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i).percent() != null) {
                return values.get(i).percent();
            }
        }
        return null;
    }

    /** 增长率 = 今年总量 ÷ 去年总量 − 1；去年（基线）为空或 0 时返回 null */
    private static BigDecimal growthRate(BigDecimal total, BigDecimal prevTotal) {
        if (total == null || prevTotal == null || BigDecimal.ZERO.compareTo(prevTotal) == 0) {
            return null;
        }
        return total.divide(prevTotal, SCALE, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }

    /** 占比 = Σ分子 ÷ Σ分母；分母为 0 时返回 null */
    private static BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || BigDecimal.ZERO.compareTo(denominator) == 0) {
            return null;
        }
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
    }

    /** 求和：全部为空返回 null，否则按 0 参与（空值视为无数据不参与） */
    private static BigDecimal sumOrNull(List<BigDecimal> values) {
        List<BigDecimal> nonNull = values.stream().filter(Objects::nonNull).toList();
        if (nonNull.isEmpty()) {
            return null;
        }
        return nonNull.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
