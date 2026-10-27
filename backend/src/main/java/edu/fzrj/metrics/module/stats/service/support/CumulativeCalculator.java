package edu.fzrj.metrics.module.stats.service.support;

import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.module.task.entity.TaskYear;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 五年累计进度（运行期口径，PRD 4.4；取值规则与五年核算 SettlementRules.fiveYearActual 一致）：
 * <ul>
 *   <li>INCREMENT 增量累计型：累计完成 = 各年审核通过完成值之和；</li>
 *   <li>STOCK / GROWTH：最新有数据年的总量；</li>
 *   <li>RATIO / DIRECT_PERCENT：最新有数据年的比率 / 百分比；</li>
 *   <li>BOOLEAN：任一年完成即 1，有数据但均未完成为 0。</li>
 * </ul>
 * 累计完成率 = 累计完成 ÷ 五年总量目标。年度目标是过程刻度，五年总量才是 100%
 * （如「五年 5 本教材」不必每年 1 本，累计完成 2 本即 40%）。
 */
public final class CumulativeCalculator {

    /** 计算结果保留 6 位小数（与表 DECIMAL(20,6) 对齐） */
    private static final int SCALE = 6;

    private CumulativeCalculator() {
    }

    /** 累计进度：actual=累计完成值（无数据为 null）；rate=累计完成率（无数据或目标空/0 为 null） */
    public record Cumulative(BigDecimal actual, BigDecimal rate) {

        public static Cumulative empty() {
            return new Cumulative(null, null);
        }
    }

    /**
     * 指标级累计进度。
     *
     * @param yearsInOrder 规划期内各年缓存，按年升序（无数据的年传 YearResult.empty()）
     * @param totalTarget  五年总量目标（indicator.total_target）
     */
    public static Cumulative of(String caliber, List<MetricCalculator.YearResult> yearsInOrder,
                                BigDecimal totalTarget) {
        BigDecimal actual = SettlementRules.fiveYearActual(caliber, yearsInOrder);
        return new Cumulative(actual, rate(actual, totalTarget));
    }

    /** B 任务级累计进度：years 顺序无关（内部按年排序），口径取自所属指标 */
    public static Cumulative ofTask(String caliber, List<TaskYear> years) {
        if (caliber == null || years == null || years.isEmpty()) {
            return Cumulative.empty();
        }
        List<TaskYear> sorted = years.stream()
                .sorted(Comparator.comparing(TaskYear::getYear))
                .toList();
        return of(caliber, toYearResults(sorted), taskTotalTarget(caliber, sorted));
    }

    /** B 五年总量目标：INCREMENT 各年之和；BOOLEAN 规划期内有计划完成年即 1；其余口径取末年目标 */
    public static BigDecimal taskTotalTarget(String caliber, List<TaskYear> years) {
        if (caliber == null || years == null || years.isEmpty()) {
            return null;
        }
        if ("BOOLEAN".equals(caliber)) {
            boolean hasPlanYear = years.stream().anyMatch(y -> y.getTarget() != null
                    && y.getTarget().compareTo(BigDecimal.ZERO) > 0);
            return hasPlanYear ? BigDecimal.ONE : null;
        }
        if ("INCREMENT".equals(caliber)) {
            List<BigDecimal> targets = years.stream().map(TaskYear::getTarget)
                    .filter(Objects::nonNull).toList();
            return targets.isEmpty() ? null : targets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return years.stream().max(Comparator.comparing(TaskYear::getYear))
                .map(TaskYear::getTarget).orElse(null);
    }

    /** 累计完成率（小数，6 位）：actual ÷ totalTarget；任一空或目标为 0 返回 null */
    public static BigDecimal rate(BigDecimal actual, BigDecimal totalTarget) {
        if (actual == null || totalTarget == null || totalTarget.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return actual.divide(totalTarget, SCALE, RoundingMode.HALF_UP);
    }

    /** 多个累计完成率的算术平均（如全计划各 A 指标累计完成率的平均）；全空返回 null */
    public static BigDecimal avgRate(List<BigDecimal> rates) {
        List<BigDecimal> safe = rates == null ? List.of() : rates.stream()
                .filter(Objects::nonNull).toList();
        if (safe.isEmpty()) {
            return null;
        }
        return safe.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(safe.size()), SCALE, RoundingMode.HALF_UP);
    }

    private static List<MetricCalculator.YearResult> toYearResults(List<TaskYear> years) {
        return years.stream()
                .map(y -> new MetricCalculator.YearResult(y.getActualValue(), y.getActualNumerator(),
                        y.getActualDenominator(), y.getActualTotal(), null, null))
                .toList();
    }
}
