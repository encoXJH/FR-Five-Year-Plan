package edu.fzrj.metrics.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.milestone.entity.Makeup;
import edu.fzrj.metrics.module.milestone.mapper.MakeupMapper;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.stats.dto.SettlementDtos;
import edu.fzrj.metrics.module.stats.dto.StatsDtos;
import edu.fzrj.metrics.module.stats.service.support.StatsAggregator;
import edu.fzrj.metrics.module.stats.service.support.SettlementRules;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 年度核算与五年核算（API 约定 15.8、PRD 5.8、数据库设计规则 4 第 3 条 / 规则 7）。
 * 年度核算：锁定 plan_year → 逐 task_year 全量重算并终态化（ACHIEVED / NOT_ACHIEVED / MAKEUP_PENDING）
 * → 未达标的 INCREMENT / BOOLEAN 缺口生成 makeup 待补做 → 指标缓存终态化。
 * 解锁：敏感操作（必须 reason），plan_year 回 ACTIVE，核算类状态回退。
 * 五年核算：全部年度 LOCKED 后进行，按口径计算五年完成值并终态判定，任务置 CLOSED。
 */
@Service
public class SettlementService {

    private final PlanService planService;
    private final PlanYearMapper planYearMapper;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final IndicatorMapper indicatorMapper;
    private final IndicatorYearTargetMapper yearTargetMapper;
    private final SubmissionMapper submissionMapper;
    private final MakeupMapper makeupMapper;
    private final NotificationMapper notificationMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final UserRefResolver userRefResolver;
    private final StatsService statsService;
    private final ScreenService screenService;

    public SettlementService(PlanService planService, PlanYearMapper planYearMapper,
                             TaskMapper taskMapper, TaskYearMapper taskYearMapper,
                             IndicatorMapper indicatorMapper, IndicatorYearTargetMapper yearTargetMapper,
                             SubmissionMapper submissionMapper, MakeupMapper makeupMapper,
                             NotificationMapper notificationMapper, OrgUnitMapper orgUnitMapper,
                             UserRefResolver userRefResolver, StatsService statsService,
                             @Lazy ScreenService screenService) {
        this.planService = planService;
        this.planYearMapper = planYearMapper;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.indicatorMapper = indicatorMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.submissionMapper = submissionMapper;
        this.makeupMapper = makeupMapper;
        this.notificationMapper = notificationMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.userRefResolver = userRefResolver;
        this.statsService = statsService;
        this.screenService = screenService;
    }

    // ---------------- 年度核算 ----------------

    /** 年度核算并锁定（幂等由 @Idempotent 保证；已锁定年度再次核算返回 60003） */
    @Transactional
    public SettlementDtos.SettleResult settle(Long planId, Integer year, Integer version) {
        Plan plan = planService.requirePlan(planId);
        planService.requireNotArchived(plan);
        planService.requireVersion(plan.getVersion(), version);
        if (!"ACTIVE".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.PLAN_STATUS_INVALID, "计划未启动执行，不能年度核算");
        }
        PlanYear planYear = requirePlanYear(planId, year);
        if ("LOCKED".equals(planYear.getStatus())) {
            throw new BizException(ErrorCode.YEAR_SETTLED);
        }
        if (!"ACTIVE".equals(planYear.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "该年度尚未激活，不能核算");
        }
        Long userId = SecurityUtils.getUserId();
        LocalDateTime now = LocalDateTime.now();

        List<Indicator> indicators = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, planId).eq(Indicator::getDeleted, 0L));
        Map<Long, Indicator> indicatorMap = indicators.stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getPlanId, planId)
                .ne(Task::getStatus, "PENDING_DISTRIBUTION")
                .eq(Task::getIsHistorical, false)
                .eq(Task::getDeleted, 0L));
        Map<Long, Task> taskMap = tasks.stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        List<TaskYear> yearRows = tasks.isEmpty() ? List.of()
                : taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .in(TaskYear::getTaskId, tasks.stream().map(Task::getId).toList())
                        .eq(TaskYear::getYear, year)
                        .eq(TaskYear::getDeleted, 0L))
                .stream().sorted(Comparator.comparing(TaskYear::getId)).toList();

        // 当年审核通过的填报按 task_year 分组（审核时间升序，STOCK / GROWTH / DIRECT_PERCENT 取最后一条）
        Map<Long, List<Submission>> approvedByTaskYear = groupApprovedByTaskYear(planId, year);
        // STOCK / GROWTH 上年总量
        Map<Long, TaskYear> prevYearByTask = prevYearRows(tasks, year);
        // 已有补做记录（PENDING / DONE）的年度行，防重新核算时重复生成
        Set<Long> makeupsCovered = coveredMakeupTaskYears(yearRows);

        long achievedCount = 0;
        long notAchievedCount = 0;
        long makeupCount = 0;
        List<Task> notAchievedTasks = new ArrayList<>();
        for (TaskYear ty : yearRows) {
            Task task = taskMap.get(ty.getTaskId());
            Indicator indicator = indicatorMap.get(ty.getIndicatorId());
            if (task == null || indicator == null) {
                continue;
            }
            String caliber = indicator.getStatCaliber();
            List<MetricCalculator.ApprovedValue> values = approvedByTaskYear
                    .getOrDefault(ty.getId(), List.of()).stream()
                    .map(s -> effectiveValue(caliber, s)).toList();
            BigDecimal prevTotal = year <= plan.getStartYear() ? indicator.getBaselineValue()
                    : prevYearByTask.containsKey(task.getId())
                    ? prevYearByTask.get(task.getId()).getActualTotal() : null;
            MetricCalculator.YearResult result = MetricCalculator.yearActual(caliber, values, prevTotal);

            ty.setActualValue(result.actualValue());
            ty.setActualNumerator(result.numerator());
            ty.setActualDenominator(result.denominator());
            ty.setActualTotal(result.total());
            ty.setCompletionRate(MetricCalculator.completionRate(caliber, result, ty.getTarget()));
            ty.setIsAchieved(MetricCalculator.achieved(indicator.getTargetDirection(), caliber, result, ty.getTarget()));

            String settleStatus = SettlementRules.settleStatus(
                    indicator.getTargetDirection(), caliber, result, ty.getTarget());
            if ("ACHIEVED".equals(settleStatus)) {
                ty.setYearStatus("ACHIEVED");
                achievedCount++;
            } else if ("NOT_ACHIEVED".equals(settleStatus)) {
                notAchievedCount++;
                notAchievedTasks.add(task);
                BigDecimal gap = SettlementRules.gapAmount(caliber, result, ty.getTarget());
                if (gap != null && !makeupsCovered.contains(ty.getId())) {
                    insertMakeup(ty, gap, plan.getEndYear(), userId);
                    makeupCount++;
                }
                ty.setYearStatus(gap != null ? "MAKEUP_PENDING" : "NOT_ACHIEVED");
            }
            // settleStatus 为 null（无目标）时保留原状态
            ty.setSettledAt(now);
            ty.setUpdatedBy(userId);
            taskYearMapper.updateById(ty);
        }

        settleIndicatorCaches(plan, year, indicators, indicatorMap, userId, now);

        planYear.setStatus("LOCKED");
        planYear.setSettledAt(now);
        planYear.setSettledBy(userId);
        planYear.setUpdatedBy(userId);
        planYearMapper.updateById(planYear);

        notifyNotAchieved(plan, year, notAchievedTasks, taskMap, indicatorMap);
        screenService.evict(planId);
        return new SettlementDtos.SettleResult(planId, year, now, yearRows.size(),
                achievedCount, notAchievedCount, makeupCount);
    }

    /** 年度核算结果查询（未核算时 items 为空；按数据范围过滤明细） */
    public SettlementDtos.SettlementView settlement(Long planId, Integer year) {
        Plan plan = planService.requirePlan(planId);
        PlanYear planYear = requirePlanYear(planId, year);
        UserRefResolver resolver = userRefResolver;
        Map<Long, SysUser> users = planYear.getSettledBy() == null ? Map.of()
                : resolver.mapOf(List.of(planYear.getSettledBy()));
        List<SettlementDtos.SettlementItem> items = List.of();
        if ("LOCKED".equals(planYear.getStatus())) {
            StatsService.ScopeFilter scope = statsService.currentScope();
            List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                            .eq(Task::getPlanId, planId)
                            .ne(Task::getStatus, "PENDING_DISTRIBUTION")
                            .eq(Task::getDeleted, 0L))
                    .stream().filter(scope::test).toList();
            Map<Long, Task> taskMap = tasks.stream().collect(Collectors.toMap(Task::getId, Function.identity()));
            List<TaskYear> yearRows = tasks.isEmpty() ? List.of()
                    : taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                            .in(TaskYear::getTaskId, tasks.stream().map(Task::getId).toList())
                            .eq(TaskYear::getYear, year)
                            .eq(TaskYear::getDeleted, 0L))
                    .stream().sorted(Comparator.comparing(TaskYear::getId)).toList();
            Map<Long, Indicator> indicatorMap = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                            .eq(Indicator::getPlanId, planId).eq(Indicator::getDeleted, 0L))
                    .stream().collect(Collectors.toMap(Indicator::getId, Function.identity()));
            Map<Long, OrgUnit> unitMap = orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                            .eq(OrgUnit::getDeleted, 0L))
                    .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
            Map<Long, Makeup> makeupByTaskYear = yearRows.isEmpty() ? Map.of()
                    : makeupMapper.selectList(new LambdaQueryWrapper<Makeup>()
                            .in(Makeup::getFromTaskYearId, yearRows.stream().map(TaskYear::getId).toList())
                            .eq(Makeup::getDeleted, 0L))
                    .stream().collect(Collectors.toMap(Makeup::getFromTaskYearId, Function.identity(),
                            (a, b) -> a));
            items = yearRows.stream().map(ty -> {
                Task task = taskMap.get(ty.getTaskId());
                Indicator indicator = indicatorMap.get(ty.getIndicatorId());
                OrgUnit unit = unitMap.get(ty.getUnitId());
                Makeup makeup = makeupByTaskYear.get(ty.getId());
                return new SettlementDtos.SettlementItem(ty.getTaskId(),
                        task == null ? null : task.getCode(),
                        indicator == null ? null : new SettlementDtos.IndicatorRef(
                                indicator.getId(), indicator.getCode(), indicator.getName()),
                        unit == null ? null : new StatsDtos.UnitRef(unit.getId(), unit.getCode(), unit.getName()),
                        StatsService.targetView(indicator == null ? null : indicator.getStatCaliber(), ty.getTarget()),
                        ty.getActualValue(), ty.getCompletionRate(), ty.getIsAchieved(), ty.getYearStatus(),
                        makeup == null ? null : makeup.getId(),
                        makeup == null ? null : makeup.getGapAmount(),
                        makeup == null ? null : makeup.getStatus());
            }).toList();
        }
        return new SettlementDtos.SettlementView(planId, year, planYear.getStatus(),
                planYear.getSettledAt(), resolver.refOf(planYear.getSettledBy(), users),
                planYear.getLastUnlockReason(), items);
    }

    // ---------------- 解锁 ----------------

    /** 解锁年度（敏感操作，必须 reason）：LOCKED → ACTIVE；核算类状态回退；未完成的补做记录取消 */
    @Transactional
    public SettlementDtos.UnlockResult unlock(Long planId, Integer year, Integer version, String reason) {
        Plan plan = planService.requirePlan(planId);
        planService.requireNotArchived(plan);
        planService.requireVersion(plan.getVersion(), version);
        PlanYear planYear = requirePlanYear(planId, year);
        if (!"LOCKED".equals(planYear.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "该年度未核算锁定，无需解锁");
        }
        Long userId = SecurityUtils.getUserId();
        planYear.setStatus("ACTIVE");
        planYear.setLastUnlockReason(reason);
        planYear.setUpdatedBy(userId);
        planYearMapper.updateById(planYear);

        Set<Long> currentTaskIds = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getPlanId, planId)
                        .eq(Task::getIsHistorical, false)
                        .eq(Task::getDeleted, 0L)
                        .select(Task::getId))
                .stream().map(Task::getId).collect(Collectors.toSet());
        List<TaskYear> settledRows = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .eq(TaskYear::getPlanId, planId)
                        .eq(TaskYear::getYear, year)
                        .isNotNull(TaskYear::getSettledAt)
                        .eq(TaskYear::getDeleted, 0L))
                .stream().filter(ty -> currentTaskIds.contains(ty.getTaskId())).toList();
        List<Long> makeupPendingRowIds = new ArrayList<>();
        for (TaskYear ty : settledRows) {
            String next = SettlementRules.statusOnUnlock(ty.getYearStatus());
            ty.setSettledAt(null);
            ty.setUpdatedBy(userId);
            if ("MAKEUP_PENDING".equals(ty.getYearStatus())) {
                makeupPendingRowIds.add(ty.getId());
            }
            if (!next.equals(ty.getYearStatus())) {
                ty.setYearStatus(next);
            }
            taskYearMapper.updateById(ty);
        }
        if (!makeupPendingRowIds.isEmpty()) {
            List<Makeup> pendings = makeupMapper.selectList(new LambdaQueryWrapper<Makeup>()
                    .in(Makeup::getFromTaskYearId, makeupPendingRowIds)
                    .eq(Makeup::getStatus, "PENDING")
                    .eq(Makeup::getDeleted, 0L));
            for (Makeup makeup : pendings) {
                makeup.setStatus("CANCELLED");
                makeup.setUpdatedBy(userId);
                makeupMapper.updateById(makeup);
            }
        }
        screenService.evict(planId);
        return new SettlementDtos.UnlockResult(planId, year, "ACTIVE", reason);
    }

    // ---------------- 五年核算 ----------------

    /** 五年核算（规划期全部年度 LOCKED 后；重复调用幂等返回已生成结果） */
    @Transactional
    public SettlementDtos.FinalSettlementView finalSettle(Long planId, Integer version) {
        Plan plan = planService.requirePlan(planId);
        planService.requireNotArchived(plan);
        planService.requireVersion(plan.getVersion(), version);
        if (plan.getFinalSettledAt() != null) {
            return buildFinalReport(plan);
        }
        List<PlanYear> years = planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                .eq(PlanYear::getPlanId, planId).eq(PlanYear::getDeleted, 0L));
        boolean allLocked = !years.isEmpty() && years.stream().allMatch(y -> "LOCKED".equals(y.getStatus()));
        if (!allLocked) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "尚有年度未核算锁定，不能进行五年核算");
        }
        Long userId = SecurityUtils.getUserId();
        LocalDateTime now = LocalDateTime.now();

        SettlementDtos.FinalSettlementView report = computeFinalReport(plan, now,
                userRefResolver.refOf(userId, userRefResolver.mapOf(List.of(userId))));

        // 任务终态化：执行中 / 已下发 → 已结束
        List<Task> openTasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getPlanId, planId)
                .in(Task::getStatus, List.of("DISTRIBUTED", "IN_PROGRESS"))
                .eq(Task::getIsHistorical, false)
                .eq(Task::getDeleted, 0L));
        for (Task task : openTasks) {
            task.setStatus("CLOSED");
            task.setUpdatedBy(userId);
            taskMapper.updateById(task);
        }

        plan.setFinalSettledAt(now);
        plan.setFinalSettledBy(userId);
        plan.setUpdatedBy(userId);
        if (planService.updatePlan(plan)) {
            Plan latest = planService.requirePlan(planId);
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + latest.getVersion());
        }
        screenService.evict(planId);
        return report;
    }

    /** 五年核算结果查询（未核算返回 10003） */
    public SettlementDtos.FinalSettlementView finalSettlement(Long planId) {
        Plan plan = planService.requirePlan(planId);
        if (plan.getFinalSettledAt() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "该计划尚未进行五年核算");
        }
        return buildFinalReport(plan);
    }

    private SettlementDtos.FinalSettlementView buildFinalReport(Plan plan) {
        Map<Long, SysUser> users = plan.getFinalSettledBy() == null ? Map.of()
                : userRefResolver.mapOf(List.of(plan.getFinalSettledBy()));
        return computeFinalReport(plan, plan.getFinalSettledAt(),
                userRefResolver.refOf(plan.getFinalSettledBy(), users));
    }

    /** 逐 A 指标按口径计算五年完成值（缓存可由审核数据全量重算，见数据库设计规则 4） */
    private SettlementDtos.FinalSettlementView computeFinalReport(Plan plan, LocalDateTime settledAt,
                                                                  edu.fzrj.metrics.common.api.UserRef settledBy) {
        List<Indicator> aList = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getPlanId, plan.getId())
                        .eq(Indicator::getLevel, "A")
                        .eq(Indicator::getIsQuantifiable, true)
                        .eq(Indicator::getDeleted, 0L))
                .stream().sorted(Comparator.comparing(Indicator::getSort).thenComparing(Indicator::getCode))
                .toList();
        List<IndicatorYearTarget> allTargets = aList.isEmpty() ? List.of()
                : yearTargetMapper.selectList(new LambdaQueryWrapper<IndicatorYearTarget>()
                        .in(IndicatorYearTarget::getIndicatorId, aList.stream().map(Indicator::getId).toList())
                        .eq(IndicatorYearTarget::getDeleted, 0L));
        Map<Long, List<IndicatorYearTarget>> byIndicator = allTargets.stream()
                .collect(Collectors.groupingBy(IndicatorYearTarget::getIndicatorId));

        long achievedCount = 0;
        List<SettlementDtos.FinalItem> items = new ArrayList<>();
        for (Indicator a : aList) {
            List<MetricCalculator.YearResult> yearResults = new ArrayList<>();
            for (int y = plan.getStartYear(); y <= plan.getEndYear(); y++) {
                int yy = y;
                IndicatorYearTarget row = byIndicator.getOrDefault(a.getId(), List.of()).stream()
                        .filter(t -> t.getYear() == yy).findFirst().orElse(null);
                yearResults.add(row == null ? MetricCalculator.YearResult.empty()
                        : new MetricCalculator.YearResult(row.getActualValue(), row.getActualNumerator(),
                        row.getActualDenominator(), row.getActualTotal(), null, null));
            }
            BigDecimal fiveYearActual = SettlementRules.fiveYearActual(a.getStatCaliber(), yearResults);
            // MANUAL 的 A：末年有人工认定值时以认定值为准
            if ("MANUAL".equals(a.getRollupMode())) {
                IndicatorYearTarget lastRow = byIndicator.getOrDefault(a.getId(), List.of()).stream()
                        .filter(t -> t.getYear() == plan.getEndYear()).findFirst().orElse(null);
                if (lastRow != null && lastRow.getManualActualValue() != null) {
                    fiveYearActual = lastRow.getManualActualValue();
                }
            }
            Boolean achieved = SettlementRules.fiveYearAchieved(
                    a.getTargetDirection(), a.getStatCaliber(), fiveYearActual, a.getTotalTarget());
            if (Boolean.TRUE.equals(achieved)) {
                achievedCount++;
            }
            BigDecimal rate = fiveYearActual == null || a.getTotalTarget() == null
                    || BigDecimal.ZERO.compareTo(a.getTotalTarget()) == 0 ? null
                    : fiveYearActual.divide(a.getTotalTarget(), 6, java.math.RoundingMode.HALF_UP);
            items.add(new SettlementDtos.FinalItem(a.getId(), a.getCode(), a.getName(), a.getNature(),
                    a.getStatCaliber(), a.getUnitLabel(), a.getTotalTarget(), fiveYearActual, rate, achieved));
        }
        return new SettlementDtos.FinalSettlementView(plan.getId(), settledAt, settledBy,
                items.size(), achievedCount, items);
    }

    // ---------------- 内部 ----------------

    /** 指标年度缓存终态化：任务载体层从 task_year 汇总；其余量化 A 按 AUTO_SUM 汇总 A+ / MANUAL 用认定值 */
    private void settleIndicatorCaches(Plan plan, Integer year, List<Indicator> indicators,
                                       Map<Long, Indicator> indicatorMap, Long userId, LocalDateTime now) {
        Map<Long, List<TaskYear>> taskYearsByIndicator = indicatorMap.isEmpty() ? Map.of()
                : taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .in(TaskYear::getIndicatorId, indicatorMap.keySet())
                        .eq(TaskYear::getYear, year)
                        .eq(TaskYear::getDeleted, 0L))
                .stream().collect(Collectors.groupingBy(TaskYear::getIndicatorId));
        Map<Long, IndicatorYearTarget> rowByIndicator = new HashMap<>();
        if (!indicatorMap.isEmpty()) {
            yearTargetMapper.selectList(new LambdaQueryWrapper<IndicatorYearTarget>()
                            .in(IndicatorYearTarget::getIndicatorId, indicatorMap.keySet())
                            .eq(IndicatorYearTarget::getYear, year)
                            .eq(IndicatorYearTarget::getDeleted, 0L))
                    .forEach(t -> rowByIndicator.put(t.getIndicatorId(), t));
        }
        // 先处理 A+，其直承 B（含历史任务）全部参与汇总
        for (Indicator indicator : indicators) {
            if (indicator.getParentId() == null) {
                continue;
            }
            List<TaskYear> ownTaskYears = taskYearsByIndicator.getOrDefault(indicator.getId(), List.of());
            MetricCalculator.YearResult result = MetricCalculator.rollup(indicator.getStatCaliber(),
                    toYearResults(ownTaskYears), prevIndicatorTotal(indicator, year, plan));
            upsertSettledCache(indicator, year, result, rowByIndicator.get(indicator.getId()), userId, now);
        }
        // 再处理量化 A：直承历史任务与 A+ 缓存合并汇总，MANUAL 仍取人工认定值
        for (Indicator a : indicators) {
            if (a.getParentId() != null || !Boolean.TRUE.equals(a.getIsQuantifiable())) {
                continue;
            }
            IndicatorYearTarget row = rowByIndicator.get(a.getId());
            if ("MANUAL".equals(a.getRollupMode())) {
                BigDecimal manual = row == null ? null : row.getManualActualValue();
                MetricCalculator.YearResult manualResult = manual == null
                        ? MetricCalculator.YearResult.empty()
                        : new MetricCalculator.YearResult(manual, null, null, null, null, null);
                upsertSettledCache(a, year, manualResult, row, userId, now);
                continue;
            }
            List<MetricCalculator.YearResult> sources = new ArrayList<>(
                    toYearResults(taskYearsByIndicator.getOrDefault(a.getId(), List.of())));
            indicators.stream()
                    .filter(i -> a.getId().equals(i.getParentId()))
                    .map(i -> {
                        IndicatorYearTarget childRow = rowByIndicator.get(i.getId());
                        return childRow == null ? MetricCalculator.YearResult.empty()
                                : new MetricCalculator.YearResult(childRow.getActualValue(),
                                childRow.getActualNumerator(), childRow.getActualDenominator(),
                                childRow.getActualTotal(), null, null);
                    })
                    .forEach(sources::add);
            MetricCalculator.YearResult result = MetricCalculator.rollup(a.getStatCaliber(), sources,
                    prevIndicatorTotal(a, year, plan));
            upsertSettledCache(a, year, result, row, userId, now);
        }
    }

    /** 核算终态写入指标缓存：达标 ACHIEVED / EXCEEDED；未达标 NOT_ACHIEVED；无目标保持原状态 */
    private void upsertSettledCache(Indicator indicator, Integer year, MetricCalculator.YearResult result,
                                    IndicatorYearTarget row, Long userId, LocalDateTime now) {
        if (row == null) {
            return;
        }
        String caliber = indicator.getStatCaliber();
        row.setActualValue(result.actualValue());
        row.setActualNumerator(result.numerator());
        row.setActualDenominator(result.denominator());
        row.setActualTotal(result.total());
        row.setCompletionRate(MetricCalculator.completionRate(caliber, result, row.getTarget()));
        if (row.getTarget() != null) {
            Boolean achieved = MetricCalculator.achieved(indicator.getTargetDirection(), caliber, result, row.getTarget());
            if (Boolean.TRUE.equals(achieved)) {
                BigDecimal rate = MetricCalculator.completionRate(caliber, result, row.getTarget());
                row.setAchieveStatus(rate != null && rate.compareTo(BigDecimal.ONE) > 0 ? "EXCEEDED" : "ACHIEVED");
            } else {
                row.setAchieveStatus("NOT_ACHIEVED");
            }
        }
        row.setCalculatedAt(now);
        row.setUpdatedBy(userId);
        yearTargetMapper.updateById(row);
    }

    private List<MetricCalculator.YearResult> toYearResults(List<TaskYear> taskYears) {
        return taskYears.stream()
                .map(ty -> new MetricCalculator.YearResult(ty.getActualValue(), ty.getActualNumerator(),
                        ty.getActualDenominator(), ty.getActualTotal(), null, null))
                .toList();
    }

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

    private Map<Long, List<Submission>> groupApprovedByTaskYear(Long planId, Integer year) {
        List<Submission> approved = submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getPlanId, planId)
                .eq(Submission::getYear, year)
                .eq(Submission::getStatus, "APPROVED")
                .eq(Submission::getDeleted, 0L)
                .orderByAsc(Submission::getReviewedAt)
                .orderByAsc(Submission::getId));
        return approved.stream().collect(Collectors.groupingBy(Submission::getTaskYearId));
    }

    private Map<Long, TaskYear> prevYearRows(List<Task> tasks, Integer year) {
        if (tasks.isEmpty()) {
            return Map.of();
        }
        return taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .in(TaskYear::getTaskId, tasks.stream().map(Task::getId).toList())
                        .eq(TaskYear::getYear, year - 1)
                        .eq(TaskYear::getDeleted, 0L))
                .stream().collect(Collectors.toMap(TaskYear::getTaskId, Function.identity()));
    }

    /** 已有 PENDING / DONE 补做记录的来源年度行（防止解锁后重新核算时重复生成） */
    private Set<Long> coveredMakeupTaskYears(List<TaskYear> yearRows) {
        if (yearRows.isEmpty()) {
            return Set.of();
        }
        return makeupMapper.selectList(new LambdaQueryWrapper<Makeup>()
                        .in(Makeup::getFromTaskYearId, yearRows.stream().map(TaskYear::getId).toList())
                        .in(Makeup::getStatus, List.of("PENDING", "DONE"))
                        .eq(Makeup::getDeleted, 0L))
                .stream().map(Makeup::getFromTaskYearId).collect(Collectors.toSet());
    }

    private void insertMakeup(TaskYear ty, BigDecimal gap, int endYear, Long userId) {
        Makeup makeup = new Makeup();
        makeup.setTaskId(ty.getTaskId());
        makeup.setFromTaskYearId(ty.getId());
        makeup.setFromYear(ty.getYear());
        makeup.setToYear(SettlementRules.makeupToYear(ty.getYear(), endYear));
        makeup.setGapAmount(gap);
        makeup.setMadeUpAmount(BigDecimal.ZERO);
        makeup.setStatus("PENDING");
        makeup.setCreatedBy(userId);
        makeup.setUpdatedBy(userId);
        makeupMapper.insert(makeup);
    }

    /** 未达标任务给学院负责人发 WARNING 通知（FR-M6-08：提示延期 / 转移 / 确认未达标） */
    private void notifyNotAchieved(Plan plan, Integer year, List<Task> notAchievedTasks,
                                   Map<Long, Task> taskMap, Map<Long, Indicator> indicatorMap) {
        Set<Long> notified = new HashSet<>();
        for (Task task : notAchievedTasks) {
            if (task.getOwnerId() == null || !notified.add(task.getId())) {
                continue;
            }
            Indicator indicator = indicatorMap.get(task.getIndicatorId());
            Notification notification = new Notification();
            notification.setUserId(task.getOwnerId());
            notification.setType("WARNING");
            notification.setTitle(year + " 年度核算未达标：" + task.getCode());
            notification.setContent("【" + plan.getName() + "】"
                    + (indicator == null ? "" : indicator.getName()) + "（" + task.getCode() + "）"
                    + year + " 年度未达标，请在规定期限内申请延期 / 转移或确认未达标。");
            notification.setBizType("task");
            notification.setBizId(task.getId());
            notification.setFeishuStatus("PENDING");
            notification.setFeishuRetryCount(0);
            notificationMapper.insert(notification);
        }
    }

    /** 审核通过后的有效值：认定值优先，未核减取填报值（与 ActualRecalcService 同口径） */
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

    private PlanYear requirePlanYear(Long planId, Integer year) {
        PlanYear planYear = planYearMapper.selectOne(new LambdaQueryWrapper<PlanYear>()
                .eq(PlanYear::getPlanId, planId)
                .eq(PlanYear::getYear, year)
                .eq(PlanYear::getDeleted, 0L));
        if (planYear == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "计划年度不存在");
        }
        return planYear;
    }
}
