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
import edu.fzrj.metrics.module.task.mapper.TaskCompletionLinkMapper;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final TaskCompletionLinkMapper completionLinkMapper;

    public ActualRecalcService(TaskYearMapper taskYearMapper, TaskMapper taskMapper,
                               SubmissionMapper submissionMapper, IndicatorMapper indicatorMapper,
                               IndicatorYearTargetMapper yearTargetMapper, PlanMapper planMapper,
                               TaskCompletionLinkMapper completionLinkMapper) {
        this.taskYearMapper = taskYearMapper;
        this.taskMapper = taskMapper;
        this.submissionMapper = submissionMapper;
        this.indicatorMapper = indicatorMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.planMapper = planMapper;
        this.completionLinkMapper = completionLinkMapper;
    }

    /** 审核前先按根 A → 历史 A+ → B 的顺序锁定本任务及直接关联任务的同学年聚合节点。 */
    public void lockAggregationForReview(Long taskYearId) {
        TaskYear taskYear = taskYearMapper.selectById(taskYearId);
        if (taskYear == null || taskYear.getDeleted() != 0L) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务年度不存在");
        }
        List<Long> taskIds = relatedTaskIds(taskYear.getTaskId());
        lockAggregates(taskIds);
        if (taskIds.size() > 1) {
            taskYearMapper.selectByTaskIdsForUpdate(taskIds, taskYear.getYear());
        }
    }

    /** 重算某 B 年度、直接关联年度并向上刷新；STOCK / GROWTH 连续重算至规划末年。 */
    public void recalcTaskYear(Long taskYearId) {
        lockAggregationForReview(taskYearId);
        TaskYear requestedYear = taskYearMapper.selectByIdForUpdate(taskYearId);
        if (requestedYear == null || requestedYear.getDeleted() != 0L) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务年度不存在");
        }
        Task task = requireTask(requestedYear.getTaskId());
        Indicator indicator = requireIndicator(task.getIndicatorId());
        Plan plan = requirePlan(task.getPlanId());
        String caliber = indicator.getStatCaliber();
        Long userId = SecurityUtils.getUserId();

        boolean recalcFollowingYears = "STOCK".equals(caliber) || "GROWTH".equals(caliber);
        List<TaskYear> taskYears = recalcFollowingYears
                ? taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .eq(TaskYear::getTaskId, task.getId())
                        .ge(TaskYear::getYear, requestedYear.getYear())
                        .le(TaskYear::getYear, plan.getEndYear())
                        .eq(TaskYear::getDeleted, 0L)
                        .orderByAsc(TaskYear::getYear)
                        .last("FOR UPDATE"))
                : List.of(requestedYear);
        for (TaskYear sourceYear : taskYears) {
            List<YearContext> contexts = new ArrayList<>();
            contexts.add(new YearContext(sourceYear, task, indicator, plan));
            for (Long targetTaskId : directLinkedTaskIds(task.getId())) {
                TaskYear targetYear = taskYearMapper.selectByTaskIdAndYearForUpdate(targetTaskId, sourceYear.getYear());
                if (targetYear != null) {
                    contexts.add(contextOf(targetYear));
                }
            }
            recalcContexts(contexts, userId);
        }
    }

    /** 新建关联时双向重算各年度既有审核进度；只使用直接关联的自身值，不间接传播。 */
    public void syncExistingProgress(Long taskAId, Long taskBId) {
        List<Long> taskIds = List.of(taskAId, taskBId).stream().distinct().sorted().toList();
        lockAggregates(taskIds);
        List<TaskYear> lockedYears = taskYearMapper.selectByTaskIdsForUpdate(taskIds, null);
        Long userId = SecurityUtils.getUserId();
        List<Integer> years = lockedYears.stream().map(TaskYear::getYear).distinct().sorted().toList();
        for (Integer year : years) {
            List<YearContext> contexts = lockedYears.stream()
                    .filter(taskYear -> year.equals(taskYear.getYear()))
                    .sorted((a, b) -> a.getId().compareTo(b.getId()))
                    .map(this::contextOf)
                    .toList();
            recalcContexts(contexts, userId);
        }
    }

    private YearContext contextOf(TaskYear taskYear) {
        Task task = requireTask(taskYear.getTaskId());
        Indicator indicator = requireIndicator(task.getIndicatorId());
        Plan plan = requirePlan(task.getPlanId());
        return new YearContext(taskYear, task, indicator, plan);
    }

    /** 先刷新所有受影响行的自身值，再选择有效值，避免双向关联读取到对方旧缓存。 */
    private void recalcContexts(List<YearContext> contexts, Long userId) {
        Map<Long, OwnState> ownStates = new HashMap<>();
        for (YearContext context : contexts) {
            ownStates.put(context.taskYear().getId(), recalcOwn(context));
        }
        for (YearContext context : contexts) {
            applyEffectiveProgress(context, ownStates, userId);
        }
        for (YearContext context : contexts) {
            recalcIndicatorYear(context.indicator(), context.taskYear().getYear(), context.plan(), userId);
        }
    }

    private OwnState recalcOwn(YearContext context) {
        TaskYear taskYear = context.taskYear();
        String caliber = context.indicator().getStatCaliber();
        List<Submission> submissions = submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getTaskYearId, taskYear.getId())
                .eq(Submission::getDeleted, 0L)
                .orderByAsc(Submission::getReviewedAt)
                .orderByAsc(Submission::getId)
                .last("FOR UPDATE"));
        boolean hasPending = submissions.stream().anyMatch(s -> "PENDING_REVIEW".equals(s.getStatus()));
        List<MetricCalculator.ApprovedValue> values = submissions.stream()
                .filter(s -> "APPROVED".equals(s.getStatus()))
                .map(s -> effectiveValue(caliber, s)).toList();
        BigDecimal prevTotal = prevTaskTotal(context.task().getId(), taskYear.getYear(),
                context.plan(), context.indicator());
        MetricCalculator.YearResult result = MetricCalculator.yearActual(caliber, values, prevTotal);
        taskYear.setOwnActualValue(result.actualValue());
        taskYear.setOwnActualNumerator(result.numerator());
        taskYear.setOwnActualDenominator(result.denominator());
        taskYear.setOwnActualTotal(result.total());
        return new OwnState(result, hasPending, prevTotal);
    }

    private void applyEffectiveProgress(YearContext context, Map<Long, OwnState> ownStates, Long userId) {
        TaskYear taskYear = context.taskYear();
        Indicator indicator = context.indicator();
        String caliber = indicator.getStatCaliber();
        OwnState ownState = ownStates.get(taskYear.getId());
        ProgressCandidate selected = selectEffectiveCandidate(context, ownStates);
        MetricCalculator.YearResult effective = effectiveResult(caliber, selected.result(), ownState.prevTotal());

        taskYear.setActualValue(effective.actualValue());
        taskYear.setActualNumerator(effective.numerator());
        taskYear.setActualDenominator(effective.denominator());
        taskYear.setActualTotal(effective.total());
        taskYear.setCompletionRate(MetricCalculator.completionRate(caliber, effective, taskYear.getTarget()));
        Boolean achieved = MetricCalculator.achieved(
                indicator.getTargetDirection(), caliber, effective, taskYear.getTarget());
        taskYear.setIsAchieved(achieved);
        taskYear.setLinkedSourceTaskYearId(selected.self() ? null : selected.sourceTaskYearId());
        taskYear.setLinkedAchieved(!selected.self() && Boolean.TRUE.equals(achieved));
        if (FLOW_STATUSES.contains(taskYear.getYearStatus())) {
            if (ownState.hasPending()) {
                taskYear.setYearStatus("PENDING_REVIEW");
            } else if (Boolean.TRUE.equals(achieved)) {
                taskYear.setYearStatus("ACHIEVED");
            } else {
                taskYear.setYearStatus("IN_PROGRESS");
            }
        }
        taskYear.setUpdatedBy(userId);
        if (taskYearMapper.updateById(taskYear) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "任务年度完成值已被他人重算，请刷新后重试");
        }
    }

    /** 候选只取自身及直接邻居的自身值，不读取邻居有效值，因此不会沿关联链传播或双计。 */
    private ProgressCandidate selectEffectiveCandidate(YearContext context, Map<Long, OwnState> ownStates) {
        TaskYear taskYear = context.taskYear();
        String caliber = context.indicator().getStatCaliber();
        String direction = context.indicator().getTargetDirection();
        ProgressCandidate selected = new ProgressCandidate(taskYear.getId(),
                ownStates.get(taskYear.getId()).result(), true);
        for (Long linkedTaskId : directLinkedTaskIds(taskYear.getTaskId())) {
            TaskYear linkedYear = taskYearMapper.selectByTaskIdAndYearForUpdate(linkedTaskId, taskYear.getYear());
            if (linkedYear == null) {
                continue;
            }
            OwnState linkedState = ownStates.get(linkedYear.getId());
            MetricCalculator.YearResult linkedResult = linkedState != null
                    ? linkedState.result() : ownResult(linkedYear, caliber);
            if (!linkedResult.hasData()) {
                continue;
            }
            ProgressCandidate candidate = new ProgressCandidate(linkedYear.getId(), linkedResult, false);
            int comparison = compareProgress(candidate.result(), selected.result(), caliber, direction);
            if (comparison > 0 || comparison == 0 && !selected.self()
                    && candidate.sourceTaskYearId() < selected.sourceTaskYearId()) {
                selected = candidate;
            }
        }
        return selected;
    }

    private MetricCalculator.YearResult ownResult(TaskYear taskYear, String caliber) {
        return new MetricCalculator.YearResult(taskYear.getOwnActualValue(), taskYear.getOwnActualNumerator(),
                taskYear.getOwnActualDenominator(), taskYear.getOwnActualTotal(),
                "GROWTH".equals(caliber) ? taskYear.getOwnActualValue() : null, null);
    }

    private int compareProgress(MetricCalculator.YearResult left, MetricCalculator.YearResult right,
                                String caliber, String direction) {
        BigDecimal leftValue = primaryValue(left, caliber);
        BigDecimal rightValue = primaryValue(right, caliber);
        if (leftValue == null || rightValue == null) {
            if (leftValue == rightValue) {
                return 0;
            }
            return leftValue == null ? -1 : 1;
        }
        int comparison = leftValue.compareTo(rightValue);
        return "LOWER_BETTER".equals(direction) ? -comparison : comparison;
    }

    private BigDecimal primaryValue(MetricCalculator.YearResult result, String caliber) {
        return "STOCK".equals(caliber) || "GROWTH".equals(caliber) ? result.total() : result.actualValue();
    }

    /** 将同一来源的原始值映射到本任务口径，并用本任务上年有效总量重算 STOCK / GROWTH。 */
    private MetricCalculator.YearResult effectiveResult(String caliber, MetricCalculator.YearResult source,
                                                         BigDecimal prevTotal) {
        if (source == null || !source.hasData()) {
            return MetricCalculator.YearResult.empty();
        }
        List<MetricCalculator.ApprovedValue> value = switch (caliber) {
            case "INCREMENT" -> List.of(MetricCalculator.ApprovedValue.increment(source.actualValue()));
            case "STOCK", "GROWTH" -> List.of(MetricCalculator.ApprovedValue.total(source.total()));
            case "RATIO" -> List.of(MetricCalculator.ApprovedValue.ratio(source.numerator(), source.denominator()));
            case "DIRECT_PERCENT" -> List.of(MetricCalculator.ApprovedValue.percent(source.actualValue()));
            case "BOOLEAN" -> List.of(MetricCalculator.ApprovedValue.bool(
                    source.actualValue() != null && BigDecimal.ONE.compareTo(source.actualValue()) == 0));
            default -> throw new BizException(ErrorCode.INTERNAL_ERROR, "未知统计口径：" + caliber);
        };
        return MetricCalculator.yearActual(caliber, value, prevTotal);
    }

    private record YearContext(TaskYear taskYear, Task task, Indicator indicator, Plan plan) {
    }

    private record OwnState(MetricCalculator.YearResult result, boolean hasPending, BigDecimal prevTotal) {
    }

    private record ProgressCandidate(Long sourceTaskYearId, MetricCalculator.YearResult result, boolean self) {
    }

    // ---------------- 逐级汇总 ----------------

    /** 刷新 A+ 或直接承载 B 的 A；A 统一合并直承 B 与 legacy A+ 缓存。 */
    private void recalcIndicatorYear(Indicator indicator, Integer year, Plan plan, Long userId) {
        if (indicator.getParentId() == null) {
            recalcAIndicatorYear(indicator, year, plan, userId);
            return;
        }
        MetricCalculator.YearResult result = rollupFromTaskYears(indicator, year, plan);
        upsertIndicatorCache(indicator, year, result, userId);
        recalcAIndicatorYear(requireIndicator(indicator.getParentId()), year, plan, userId);
    }

    private void recalcAIndicatorYear(Indicator a, Integer year, Plan plan, Long userId) {
        if (!"AUTO_SUM".equals(a.getRollupMode())) {
            return;
        }
        List<Indicator> children = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getParentId, a.getId())
                .eq(Indicator::getDeleted, 0L));
        List<MetricCalculator.YearResult> sources = new ArrayList<>(taskYearResults(a, year));
        children.stream().map(child -> cacheOfCurrentYear(child, year)).forEach(sources::add);
        MetricCalculator.YearResult result = MetricCalculator.rollup(
                a.getStatCaliber(), sources, prevIndicatorTotal(a, year, plan));
        upsertIndicatorCache(a, year, result, userId);
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
                        .eq(TaskYear::getDeleted, 0L)
                        .orderByAsc(TaskYear::getId)
                        .last("FOR UPDATE"))
                .stream()
                .map(ty -> new MetricCalculator.YearResult(ty.getActualValue(), ty.getActualNumerator(),
                        ty.getActualDenominator(), ty.getActualTotal(), null, null))
                .toList();
    }

    private MetricCalculator.YearResult cacheOfCurrentYear(Indicator indicator, Integer year) {
        IndicatorYearTarget row = yearTargetMapper.selectOne(new LambdaQueryWrapper<IndicatorYearTarget>()
                .eq(IndicatorYearTarget::getIndicatorId, indicator.getId())
                .eq(IndicatorYearTarget::getYear, year)
                .eq(IndicatorYearTarget::getDeleted, 0L)
                .last("FOR UPDATE"));
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
                .eq(IndicatorYearTarget::getDeleted, 0L)
                .last("FOR UPDATE"));
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
        if (yearTargetMapper.updateById(row) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "指标年度完成值已被他人重算，请刷新后重试");
        }
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
                .eq(TaskYear::getDeleted, 0L)
                .last("FOR UPDATE"));
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
                .eq(IndicatorYearTarget::getDeleted, 0L)
                .last("FOR UPDATE"));
        return prev == null ? null : prev.getActualTotal();
    }

    private List<Long> relatedTaskIds(Long taskId) {
        List<Long> taskIds = new ArrayList<>(directLinkedTaskIds(taskId));
        taskIds.add(taskId);
        return taskIds.stream().distinct().sorted().toList();
    }

    private List<Long> directLinkedTaskIds(Long taskId) {
        return completionLinkMapper.selectActiveByTaskId(taskId).stream()
                .map(link -> taskId.equals(link.getTaskAId()) ? link.getTaskBId() : link.getTaskAId())
                .distinct().sorted().toList();
    }

    private void lockAggregates(List<Long> taskIds) {
        List<Indicator> indicators = taskIds.stream()
                .map(taskId -> requireIndicator(requireTask(taskId).getIndicatorId()))
                .toList();
        Map<Long, Indicator> roots = new HashMap<>();
        Map<Long, Indicator> aPluses = new HashMap<>();
        for (Indicator indicator : indicators) {
            if (indicator.getParentId() == null) {
                roots.put(indicator.getId(), indicator);
            } else {
                roots.computeIfAbsent(indicator.getParentId(), this::requireIndicator);
                aPluses.put(indicator.getId(), indicator);
            }
        }
        roots.values().stream().sorted((a, b) -> a.getId().compareTo(b.getId()))
                .forEach(this::lockIndicatorIfAutoSum);
        aPluses.keySet().stream().sorted().forEach(this::lockIndicator);
    }

    private void lockIndicatorIfAutoSum(Indicator indicator) {
        if ("AUTO_SUM".equals(indicator.getRollupMode())) {
            lockIndicator(indicator.getId());
        }
    }

    private void lockIndicator(Long indicatorId) {
        Indicator locked = indicatorMapper.selectOne(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getId, indicatorId)
                .eq(Indicator::getDeleted, 0L)
                .last("FOR UPDATE"));
        if (locked == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "指标不存在");
        }
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
