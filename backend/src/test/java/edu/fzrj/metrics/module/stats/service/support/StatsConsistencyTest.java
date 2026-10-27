package edu.fzrj.metrics.module.stats.service.support;

import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator.YearResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 汇总一致性纯逻辑验证（大屏数字与统计接口一致）：
 * 同一份 B 年度数据，总览计数 = 各单元计数之和 = 年度趋势合计；
 * A+ 完成值汇总（MetricCalculator）= 各 B 直接求和（INCREMENT）/ Σ分子÷Σ分母（RATIO）。
 */
class StatsConsistencyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 模拟一行 task_year 统计输入 */
    private record Row(String unit, int year, BigDecimal target, String yearStatus,
                       YearResult result) {
    }

    private static final List<Row> ROWS = List.of(
            new Row("C01", 2026, bd("3"), "ACHIEVED",
                    new YearResult(bd("3"), null, null, null, null, null)),
            new Row("C01", 2026, bd("2"), "IN_PROGRESS",
                    new YearResult(bd("1"), null, null, null, null, null)),
            new Row("C02", 2026, bd("1"), "MAKEUP_PENDING",
                    new YearResult(bd("0"), null, null, null, null, null)),
            // 无年度目标（BOOLEAN 非完成年），不计入统计基数
            new Row("C03", 2026, null, "NOT_ACTIVATED",
                    YearResult.empty()));

    @Test
    void 总览完成率等于各单元达标数合计除以基数合计() {
        List<Row> countable = ROWS.stream().filter(r -> StatsAggregator.countable(r.target())).toList();
        long totalAchieved = countable.stream()
                .filter(r -> StatsAggregator.isAchievedStatus(r.yearStatus())).count();
        BigDecimal overall = StatsAggregator.rate(totalAchieved, countable.size());

        Map<String, List<Row>> byUnit = countable.stream().collect(Collectors.groupingBy(Row::unit));
        long sumUnits = byUnit.values().stream().mapToLong(List::size).sum();
        long sumUnitAchieved = byUnit.values().stream()
                .mapToLong(rows -> rows.stream()
                        .filter(r -> StatsAggregator.isAchievedStatus(r.yearStatus())).count())
                .sum();
        assertEquals(countable.size(), sumUnits);
        assertEquals(totalAchieved, sumUnitAchieved);
        assertEquals(0, overall.compareTo(StatsAggregator.rate(sumUnitAchieved, sumUnits)));
        // C01：2 任务 1 达标；C02：1 任务 0 达标；总体 1/3
        assertEquals(0, bd("0.333333").compareTo(overall));
    }

    @Test
    void 年度趋势合计等于总览计数() {
        List<Row> countable = ROWS.stream().filter(r -> StatsAggregator.countable(r.target())).toList();
        Map<Integer, List<Row>> byYear = countable.stream().collect(Collectors.groupingBy(Row::year));
        long trendTotal = byYear.values().stream().mapToLong(List::size).sum();
        long trendAchieved = byYear.values().stream()
                .mapToLong(rows -> rows.stream()
                        .filter(r -> StatsAggregator.isAchievedStatus(r.yearStatus())).count())
                .sum();
        assertEquals(countable.size(), trendTotal);
        assertEquals(countable.stream().filter(r -> StatsAggregator.isAchievedStatus(r.yearStatus())).count(),
                trendAchieved);
    }

    @Test
    void 增量口径A加汇总等于各B加直接求和() {
        YearResult rollup = MetricCalculator.rollup("INCREMENT",
                ROWS.stream().map(Row::result).toList(), null);
        BigDecimal directSum = ROWS.stream().map(r -> r.result().actualValue())
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, directSum.compareTo(rollup.actualValue()));
        assertEquals(0, bd("4").compareTo(rollup.actualValue()));
    }

    @Test
    void 占比口径汇总为总分除总分而非平均() {
        List<YearResult> children = List.of(
                new YearResult(null, bd("60"), bd("100"), null, null, null),
                new YearResult(null, bd("28"), bd("50"), null, null, null));
        YearResult rollup = MetricCalculator.rollup("RATIO", children, null);
        // 88/150 ≈ 0.586667；错误平均 (0.6+0.56)/2 = 0.58
        assertEquals(0, bd("0.586667").compareTo(rollup.actualValue()));
        assertNotEquals(0, bd("0.58").compareTo(rollup.actualValue()));
    }

    @Test
    void 核算口径与统计口径一致_目标零时完成率为空() {
        YearResult result = new YearResult(bd("4"), null, null, null, null, null);
        assertEquals(null, MetricCalculator.completionRate("INCREMENT", result, BigDecimal.ZERO));
        // 同一结果在核算终态判定中：目标为 null 不判定，目标为 0 时由 countable 排除出统计基数
        assertEquals(null, SettlementRules.settleStatus("HIGHER_BETTER", "INCREMENT", result, null));
        assertEquals(false, StatsAggregator.countable(BigDecimal.ZERO));
    }
}
