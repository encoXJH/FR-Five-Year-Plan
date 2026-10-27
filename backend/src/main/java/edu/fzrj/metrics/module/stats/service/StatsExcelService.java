package edu.fzrj.metrics.module.stats.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.service.support.EnumAliases;
import edu.fzrj.metrics.module.stats.dto.StatsDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统计报表 Excel（ExportType=STATS_REPORT，API 约定 15.8 / FR-M8-04）：
 * 四个 sheet——总览、单元排名、A 级指标、任务明细。
 * 异步线程没有 SecurityContext，数据范围由 scopeOwner 决定（null=全校，其余=负责人 id）。
 */
@Service
public class StatsExcelService {

    private static final Map<String, String> YEAR_STATUS_LABEL = Map.of(
            "NOT_ACTIVATED", "未激活", "IN_PROGRESS", "进行中", "PENDING_REVIEW", "待审核",
            "ADJUSTING", "调整审批中", "ACHIEVED", "已达标", "NOT_ACHIEVED", "未达标",
            "MAKEUP_PENDING", "待补做", "MADE_UP", "已补做");

    private final StatsService statsService;

    public StatsExcelService(StatsService statsService) {
        this.statsService = statsService;
    }

    public byte[] buildStatsReport(Long planId, Integer year, Long scopeOwner) {
        StatsService.ScopeFilter scope = scopeOwner == null
                ? StatsService.ScopeFilter.allData() : statsService.scopeForOwner(scopeOwner);
        StatsDtos.Overview overview = statsService.overview(planId, year, scope);
        int resolvedYear = overview.year();
        List<StatsDtos.UnitStat> byUnit = statsService.byUnit(planId, resolvedYear);
        List<StatsDtos.IndicatorStat> byIndicator = statsService.byIndicator(planId, resolvedYear);
        List<List<Object>> taskRows = taskRows(planId, resolvedYear, scope);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            writer.write(overviewRows(overview), sheet("总览",
                    List.of("统计项", "值")));
            writer.write(unitRows(byUnit), sheet("单元排名",
                    List.of("排名", "单元编码", "单元名称", "任务数", "已达标", "完成率")));
            writer.write(indicatorRows(byIndicator), sheet("A 级指标",
                    List.of("A 编号", "A 名称", "A+ 数", "B 任务数", "已达标", "完成率", "五年累计完成率")));
            writer.write(taskRows, sheet("任务明细",
                    List.of("任务编号", "指标编号", "指标名称", "统计口径", "单位", "单元编码", "单元名称",
                            "年度目标", "完成值", "完成率", "年度状态")));
        }
        return out.toByteArray();
    }

    private List<List<Object>> overviewRows(StatsDtos.Overview o) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("统计年度", o.year()));
        rows.add(List.of("时间进度", percent(o.timeProgress())));
        rows.add(List.of("有目标任务数", o.taskCount()));
        rows.add(List.of("已达标", o.achievedCount()));
        rows.add(List.of("未达标", o.notAchievedCount()));
        rows.add(List.of("进行中", o.inProgressCount()));
        rows.add(List.of("待补做", o.makeupPendingCount()));
        rows.add(List.of("已补做", o.madeUpCount()));
        rows.add(List.of("总完成率", percent(o.overallCompletionRate())));
        rows.add(List.of("约束性达标", o.constraintAchieved() + "/" + o.constraintTotal()));
        rows.add(List.of("发展性达标", o.developmentAchieved() + "/" + o.developmentTotal()));
        rows.add(List.of("滞后预警数", o.warningCount()));
        return rows;
    }

    private List<List<Object>> unitRows(List<StatsDtos.UnitStat> stats) {
        return stats.stream()
                .map(s -> {
                    List<Object> row = new ArrayList<>();
                    row.add(s.rank());
                    row.add(s.unit() == null ? "" : s.unit().code());
                    row.add(s.unit() == null ? "" : s.unit().name());
                    row.add(s.taskCount());
                    row.add(s.achievedCount());
                    row.add(percent(s.completionRate()));
                    return row;
                })
                .collect(Collectors.toList());
    }

    private List<List<Object>> indicatorRows(List<StatsDtos.IndicatorStat> stats) {
        return stats.stream()
                .map(s -> {
                    List<Object> row = new ArrayList<>();
                    row.add(s.code());
                    row.add(s.name());
                    row.add(s.aPlusCount());
                    row.add(s.taskCount());
                    row.add(s.achievedCount());
                    row.add(percent(s.completionRate()));
                    row.add(percent(s.cumulativeRate()));
                    return row;
                })
                .collect(Collectors.toList());
    }

    private List<List<Object>> taskRows(Long planId, int year, StatsService.ScopeFilter scope) {
        edu.fzrj.metrics.module.plan.entity.Plan plan = statsService.requirePlan(planId);
        StatsService.PlanContext ctx = statsService.loadContext(plan, year, scope);
        return ctx.taskYears().stream()
                .sorted(Comparator.comparing(ty -> {
                    Task task = ctx.tasks().get(ty.getTaskId());
                    return task == null ? "" : task.getCode();
                }))
                .map(ty -> {
                    Task task = ctx.tasks().get(ty.getTaskId());
                    Indicator indicator = ctx.indicators().get(ty.getIndicatorId());
                    var unit = ctx.units().get(ty.getUnitId());
                    String caliber = indicator == null ? null : indicator.getStatCaliber();
                    List<Object> row = new ArrayList<>();
                    row.add(task == null ? "" : task.getCode());
                    row.add(indicator == null ? "" : indicator.getCode());
                    row.add(indicator == null ? "" : indicator.getName());
                    row.add(caliber == null ? "" : EnumAliases.label(caliber));
                    row.add(indicator == null ? "" : indicator.getUnitLabel());
                    row.add(unit == null ? "" : unit.getCode());
                    row.add(unit == null ? "" : unit.getName());
                    Object target = StatsService.targetView(caliber, ty.getTarget());
                    row.add(target instanceof Boolean b ? (b ? "是" : "") : target == null ? "" : target);
                    row.add(ty.getActualValue() == null ? "" : ty.getActualValue());
                    row.add(percent(ty.getCompletionRate()));
                    row.add(YEAR_STATUS_LABEL.getOrDefault(ty.getYearStatus(), ty.getYearStatus()));
                    return row;
                })
                .collect(Collectors.toList());
    }

    private WriteSheet sheet(String name, List<String> head) {
        List<List<String>> headRows = head.stream().map(List::of).collect(Collectors.toList());
        return EasyExcel.writerSheet(name).head(headRows).build();
    }

    /** 小数完成率 → 百分比数值（Excel 中显示为 0.83 → 83.33% 文本，避免单元格格式依赖） */
    private static Object percent(BigDecimal rate) {
        if (rate == null) {
            return "";
        }
        return rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }
}
