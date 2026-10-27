package edu.fzrj.metrics.module.stats.service.support;

import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 年度核算 / 五年核算纯逻辑（PRD 4.4.6、5.8；数据库设计规则 4 第 3 条）。
 * 达标判定复用 MetricCalculator（HIGHER_BETTER ≥ 目标、LOWER_BETTER ≤ 目标）。
 */
public final class SettlementRules {

    private SettlementRules() {
    }

    /**
     * 年度核算终态判定：ACHIEVED / NOT_ACHIEVED；目标为空（如 BOOLEAN 非完成年）返回 null 不判定。
     */
    public static String settleStatus(String direction, String caliber,
                                      MetricCalculator.YearResult result, BigDecimal target) {
        if (target == null) {
            return null;
        }
        Boolean achieved = MetricCalculator.achieved(direction, caliber, result, target);
        return Boolean.TRUE.equals(achieved) ? "ACHIEVED" : "NOT_ACHIEVED";
    }

    /**
     * 是否生成待补做记录：INCREMENT 缺口按数量补做；BOOLEAN 到计划完成年未完成按 1 补做（PRD 4.4.5）。
     * 存量 / 增长率 / 占比 / 直填百分比的缺口无法表达为可补做数量，只记未达标。
     */
    public static boolean makeupEligible(String caliber) {
        return "INCREMENT".equals(caliber) || "BOOLEAN".equals(caliber);
    }

    /**
     * 缺口数量：INCREMENT = max(0, 目标 − 完成值)；BOOLEAN 未完成 = 1。
     * 无缺口（已达标签调用方拦截）或非补做口径返回 null。
     */
    public static BigDecimal gapAmount(String caliber, MetricCalculator.YearResult result, BigDecimal target) {
        if (!makeupEligible(caliber) || target == null) {
            return null;
        }
        if ("BOOLEAN".equals(caliber)) {
            return BigDecimal.ONE;
        }
        BigDecimal actual = result == null || result.actualValue() == null
                ? BigDecimal.ZERO : result.actualValue();
        BigDecimal gap = target.subtract(actual);
        return gap.compareTo(BigDecimal.ZERO) > 0 ? gap : null;
    }

    /** 补做年度：下一年；核算年度已是规划末年时记为末年本身（五年核算后补做） */
    public static int makeupToYear(int year, int endYear) {
        return Math.min(year + 1, endYear);
    }

    /** 解锁时年度状态回退：未达标 / 待补做 → 进行中；其余状态不动 */
    public static String statusOnUnlock(String yearStatus) {
        if ("NOT_ACHIEVED".equals(yearStatus) || "MAKEUP_PENDING".equals(yearStatus)) {
            return "IN_PROGRESS";
        }
        return yearStatus;
    }

    /**
     * 五年完成值主值（PRD 4.4 各口径）：
     * INCREMENT 各年完成值之和；STOCK / GROWTH 末年（最后一个有数据年）总量；
     * RATIO / DIRECT_PERCENT 末年比率 / 百分比；BOOLEAN 任一年完成即 1。
     *
     * @param yearsInOrder 规划期内各年缓存，按年升序（无数据的年传 YearResult.empty()）
     */
    public static BigDecimal fiveYearActual(String caliber, List<MetricCalculator.YearResult> yearsInOrder) {
        List<MetricCalculator.YearResult> safe = yearsInOrder == null ? List.of() : yearsInOrder;
        return switch (caliber) {
            case "INCREMENT" -> {
                List<BigDecimal> values = safe.stream().map(MetricCalculator.YearResult::actualValue)
                        .filter(Objects::nonNull).toList();
                yield values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            case "STOCK", "GROWTH" -> lastNonNull(safe.stream()
                    .map(MetricCalculator.YearResult::total).toList());
            case "RATIO", "DIRECT_PERCENT" -> lastNonNull(safe.stream()
                    .map(MetricCalculator.YearResult::actualValue).toList());
            case "BOOLEAN" -> {
                List<BigDecimal> values = safe.stream().map(MetricCalculator.YearResult::actualValue)
                        .filter(Objects::nonNull).toList();
                if (values.isEmpty()) {
                    yield null;
                }
                yield values.stream().anyMatch(v -> BigDecimal.ONE.compareTo(v) == 0)
                        ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            default -> throw new IllegalArgumentException("未知统计口径：" + caliber);
        };
    }

    /** 五年达标判定：目标或完成值为空返回 null；BOOLEAN 完成值 1 即达标 */
    public static Boolean fiveYearAchieved(String direction, String caliber,
                                           BigDecimal fiveYearActual, BigDecimal totalTarget) {
        if (fiveYearActual == null || totalTarget == null) {
            return null;
        }
        int cmp = fiveYearActual.compareTo(totalTarget);
        return "LOWER_BETTER".equals(direction) ? cmp <= 0 : cmp >= 0;
    }

    private static BigDecimal lastNonNull(List<BigDecimal> values) {
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i) != null) {
                return values.get(i);
            }
        }
        return null;
    }
}
