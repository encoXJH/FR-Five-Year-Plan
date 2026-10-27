package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 完成值重算与逐级汇总（PRD 4.5：B → A+ → A；只有审核通过的数据计入）：
 * 审核通过 / 驳回后在同一事务内调用，重算 task_year 缓存与年度状态，
 * 再向上刷新 A+、A 的 indicator_year_target 缓存（A 为 MANUAL 时不自动汇总，留手工认定）。
 */
@Service
public class ActualRecalcService {

    /** 允许被填报 / 审核流程翻转的年度状态（核算类状态由年度核算流程负责，不在此覆盖） */
    private static final Set<String> FLOW_STATUSES = Set.of("NOT_ACTIVATED", "IN_PROGRESS", "PENDING_REVIEW", "ACHIEVED");

    private final TaskYearMapper taskYearMapper;
    private final TaskMapper taskMapper;
    private final SubmissionMapper submissionMapper;
    private final IndicatorMapper indicatorMapper;
    private final IndicatorYearTargetMapper yearTargetMapper;
    private final PlanMapper planMapper;

    public ActualRecalcService(TaskYearMapper taskYearMapper, TaskMapper taskMapper,
                               SubmissionMapper submissionMapper, IndicatorMapper indicatorMapper,
                               IndicatorYearTargetMapper yearTargetMapper, PlanMapper planMapper) {
        this.taskYearMapper = taskYearMapper;
        this.taskMapper = taskMapper;
        this.submissionMapper = submissionMapper;
        this.indicatorMapper = indicatorMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.planMapper = planMapper;
    }

    /** 重算某 B 年度的完成值缓存与年度状态，并逐级向上刷新 A+、A 缓存 */
    public void recalcTaskYear(Long taskYearId) {
        TaskYear taskYear = taskYearMapper.selectById(taskYearId);
        if (taskYear == null || taskYear.getDeleted() != 0L) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务年度不存在");
        }
        Task task = requireTask(taskYear.getTaskId());
        Indicator indicator = requireIndicator(task.getIndicatorId());
        Plan plan = requirePlan(task.getPlanId());
        String caliber = indicator.getStatCaliber();
        Long userId = SecurityUtils.getUserId();

        List<Submission> approved = submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getTaskYearId, taskYear.getId())
                .eq(Submission::getStatus, "APPROVED")
                .eq(Submission::getDeleted, 0L)
                .orderByAsc(Submission::getReviewedAt)
                .orderByAsc(Submission::getId));
        List<MetricCalculator.ApprovedValue> values = approved.stream()
                .map(s -> effectiveValue(caliber, s)).toList();
        MetricCalculator.YearResult result = MetricCalculator.yearActual(
                caliber, values, prevTaskTotal(task.getId(), taskYear.getYear(), plan, indicator));

        boolean hasPending = submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getTaskYearId, taskYear.getId())
                .eq(Submission::getStatus, "PENDING_REVIEW")
                .eq(Submission::getDeleted, 0L)) > 0;

        taskYear.setActualValue(result.actualValue());
        taskYear.setActualNumerator(result.numerator());
        taskYear.setActualDenominator(result.denominator());
        taskYear.setActualTotal(result.total());
        taskYear.setCompletionRate(MetricCalculator.completionRate(caliber, result, taskYear.getTarget()));
        taskYear.setIsAchieved(MetricCalculator.achieved(
                indicator.getTargetDirection(), caliber, result, taskYear.getTarget()));
        if (FLOW_STATUSES.contains(taskYear.getYearStatus())) {
            taskYear.setYearStatus(hasPending ? "PENDING_REVIEW"
                    : Boolean.TRUE.equals(taskYear.getIsAchieved()) ? "ACHIEVED" : "IN_PROGRESS");
        }
        taskYear.setUpdatedBy(userId);
        taskYearMapper.updateById(taskYear);

        recalcIndicatorYear(indicator, taskYear.getYear(), plan, userId);
    }

    // ---------------- 逐级汇总 ----------------

    /** 刷新某指标（A+ 或不可下分 A）某年的缓存，并在需要时继续向上刷新所属 A */
    private void recalcIndicatorYear(Indicator indicator, Integer year, Plan plan, Long userId) {
        if (indicator.getParentId() == null && "MANUAL".equals(indicator.getRollupMode())) {
            // MANUAL 的 A（含不可下分 A）由校级项目负责人手工认定，不自动汇总
            return;
        }
        MetricCalculator.YearResult result = rollupFromTaskYears(indicator, year, plan);
        upsertIndicatorCache(indicator, year, result, userId);
        if (indicator.getParentId() == null) {
            return;
        }
        Indicator parent = requireIndicator(indicator.getParentId());
        if (!"AUTO_SUM".equals(parent.getRollupMode())) {
            // MANUAL：A 的完成值由校级项目负责人手工认定，不自动汇总
            return;
        }
        List<Indicator> siblings = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getParentId, parent.getId())
                .eq(Indicator::getDeleted, 0L));
        List<MetricCalculator.YearResult> sources = new ArrayList<>(taskYearResults(parent, year));
        siblings.stream()
                .map(child -> cacheOfCurrentYear(child, year))
                .forEach(sources::add);
        MetricCalculator.YearResult parentResult = MetricCalculator.rollup(
                parent.getStatCaliber(), sources, prevIndicatorTotal(parent, year, plan));
        upsertIndicatorCache(parent, year, parentResult, userId);
    }

    /** 本级指标某年缓存 = 其全部 B 任务该年 task_year 缓存的汇总 */
    private MetricCalculator.YearResult rollupFromTaskYears(Indicator indicator, Integer year, Plan plan) {
        return MetricCalculator.rollup(indicator.getStatCaliber(), taskYearResults(indicator, year),
                prevIndicatorTotal(indicator, year, plan));
    }

    private List<MetricCalculator.YearResult> taskYearResults(Indicator indicator, Integer year) {
        return taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .eq(TaskYear::getIndicatorId, indicator.getId())
                        .eq(TaskYear::getYear, year)
                        .eq(TaskYear::getDeleted, 0L))
                .stream()
                .map(ty -> new MetricCalculator.YearResult(ty.getActualValue(), ty.getActualNumerator(),
                        ty.getActualDenominator(), ty.getActualTotal(), null, null))
                .toList();
    }

    private MetricCalculator.YearResult cacheOfCurrentYear(Indicator indicator, Integer year) {
        IndicatorYearTarget row = yearTargetMapper.selectOne(new LambdaQueryWrapper<IndicatorYearTarget>()
                .eq(IndicatorYearTarget::getIndicatorId, indicator.getId())
                .eq(IndicatorYearTarget::getYear, year)
                .eq(IndicatorYearTarget::getDeleted, 0L));
        if (row == null) {
            return MetricCalculator.YearResult.empty();
        }
        return new MetricCalculator.YearResult(row.getActualValue(), row.getActualNumerator(),
                row.getActualDenominator(), row.getActualTotal(), null, null);
    }

    private void upsertIndicatorCache(Indicator indicator, Integer year,
                                      MetricCalculator.YearResult result, Long userId) {
        IndicatorYearTarget row = yearTargetMapper.selectOne(new LambdaQueryWrapper<IndicatorYearTarget>()
                .eq(IndicatorYearTarget::getIndicatorId, indicator.getId())
                .eq(IndicatorYearTarget::getYear, year)
                .eq(IndicatorYearTarget::getDeleted, 0L));
        if (row == null) {
            return;
        }
        String caliber = indicator.getStatCaliber();
        row.setActualValue(result.actualValue());
        row.setActualNumerator(result.numerator());
        row.setActualDenominator(result.denominator());
        row.setActualTotal(result.total());
        row.setCompletionRate(MetricCalculator.completionRate(caliber, result, row.getTarget()));
        row.setAchieveStatus(MetricCalculator.achieveStatus(
                indicator.getTargetDirection(), caliber, result, row.getTarget()));
        row.setCalculatedAt(LocalDateTime.now());
        row.setUpdatedBy(userId);
        yearTargetMapper.updateById(row);
    }

    // ---------------- 有效值 / 上年总量 ----------------

    /** 审核通过后的有效值：认定值优先，未核减取填报值 */
    private MetricCalculator.ApprovedValue effectiveValue(String caliber, Submission s) {
        return switch (caliber) {
            case "INCREMENT" -> MetricCalculator.ApprovedValue.increment(
                    s.getApprovedAmount() != null ? s.getApprovedAmount() : s.getAmount());
            case "STOCK", "GROWTH" -> MetricCalculator.ApprovedValue.total(
                    s.getApprovedTotal() != null ? s.getApprovedTotal() : s.getTotal());
            case "RATIO" -> MetricCalculator.ApprovedValue.ratio(
                    s.getApprovedNumerator() != null ? s.getApprovedNumerator() : s.getNumerator(),
                    s.getApprovedDenominator() != null ? s.getApprovedDenominator() : s.getDenominator());
            case "DIRECT_PERCENT" -> MetricCalculator.ApprovedValue.percent(
                    s.getApprovedPercent() != null ? s.getApprovedPercent() : s.getPercent());
            case "BOOLEAN" -> MetricCalculator.ApprovedValue.bool(
                    s.getApprovedAchieved() != null ? s.getApprovedAchieved() : s.getAchieved());
            default -> throw new BizException(ErrorCode.INTERNAL_ERROR, "未知统计口径：" + caliber);
        };
    }

    /** B 上年完成总量（STOCK / GROWTH 用；第一年用指标基线值） */
    private BigDecimal prevTaskTotal(Long taskId, Integer year, Plan plan, Indicator indicator) {
        if (year <= plan.getStartYear()) {
            return indicator.getBaselineValue();
        }
        TaskYear prev = taskYearMapper.selectOne(new LambdaQueryWrapper<TaskYear>()
                .eq(TaskYear::getTaskId, taskId)
                .eq(TaskYear::getYear, year - 1)
                .eq(TaskYear::getDeleted, 0L));
        return prev == null ? null : prev.getActualTotal();
    }

    /** 指标级上年完成总量（STOCK / GROWTH 汇总用；第一年用基线值） */
    private BigDecimal prevIndicatorTotal(Indicator indicator, Integer year, Plan plan) {
        if (year <= plan.getStartYear()) {
            return indicator.getBaselineValue();
        }
        IndicatorYearTarget prev = yearTargetMapper.selectOne(new LambdaQueryWrapper<IndicatorYearTarget>()
                .eq(IndicatorYearTarget::getIndicatorId, indicator.getId())
                .eq(IndicatorYearTarget::getYear, year - 1)
                .eq(IndicatorYearTarget::getDeleted, 0L));
        return prev == null ? null : prev.getActualTotal();
    }

    private Task requireTask(Long taskId) {
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getId, taskId)
                .eq(Task::getDeleted, 0L));
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return task;
    }

    private Indicator requireIndicator(Long indicatorId) {
        Indicator indicator = indicatorMapper.selectOne(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getId, indicatorId)
                .eq(Indicator::getDeleted, 0L));
        if (indicator == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "指标不存在");
        }
        return indicator;
    }

    private Plan requirePlan(Long planId) {
        Plan plan = planMapper.selectOne(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getId, planId)
                .eq(Plan::getDeleted, 0L));
        if (plan == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "计划不存在");
        }
        return plan;
    }
}
