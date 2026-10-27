package edu.fzrj.metrics.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Direction;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.stats.dto.StatsDtos;
import edu.fzrj.metrics.module.stats.service.support.CumulativeCalculator;
import edu.fzrj.metrics.module.stats.service.support.StatsAggregator;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysSetting;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysSettingMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统计（API 约定 15.8）：总览 / 按单元 / 按 A 级指标 / A→A+→B 下钻树 / 指标趋势。
 * 数据范围：overview / tree / trend 按数据范围过滤（越权 20005）；
 * by-unit / by-indicator 为全校聚合视图（PRD Q12：学院可见全校概览与排名，不见明细）。
 * 口径：完成率一律「达标任务数 ÷ 有正目标的任务数」（数据库设计查询 h）；
 * 树与汇总的完成值由可见 B 的 task_year 缓存按 MetricCalculator 逐级汇总（与审核时落库口径一致）。
 */
@Service
public class StatsService {

    /** 滞后预警阈值参数键（V2 种子；缺省 0.5） */
    public static final String LAG_THRESHOLD_KEY = "warning.lag.threshold";
    private static final BigDecimal DEFAULT_LAG_THRESHOLD = new BigDecimal("0.5");

    /**
     * 数据范围过滤：all=全校；unitId=本单元；indicatorIds=本人负责指标（A 显式负责 + A+ 显式负责；
     * A+ 未设负责人时经 task.topIndicatorId 命中所属 A）。
     */
    public record ScopeFilter(boolean all, Long unitId, Set<Long> indicatorIds) {

        public static ScopeFilter allData() {
            return new ScopeFilter(true, null, Set.of());
        }

        public boolean test(Task task) {
            if (all) {
                return true;
            }
            if (unitId != null && unitId.equals(task.getUnitId())) {
                return true;
            }
            return indicatorIds.contains(task.getIndicatorId())
                    || indicatorIds.contains(task.getTopIndicatorId());
        }
    }

    private final PlanService planService;
    private final PlanYearMapper planYearMapper;
    private final IndicatorMapper indicatorMapper;
    private final IndicatorYearTargetMapper yearTargetMapper;
    private final DirectionMapper directionMapper;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final SysSettingMapper sysSettingMapper;
    private final DataScopeService dataScopeService;

    public StatsService(PlanService planService, PlanYearMapper planYearMapper,
                        IndicatorMapper indicatorMapper, IndicatorYearTargetMapper yearTargetMapper,
                        DirectionMapper directionMapper, TaskMapper taskMapper,
                        TaskYearMapper taskYearMapper, OrgUnitMapper orgUnitMapper,
                        SysSettingMapper sysSettingMapper, DataScopeService dataScopeService) {
        this.planService = planService;
        this.planYearMapper = planYearMapper;
        this.indicatorMapper = indicatorMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.directionMapper = directionMapper;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.sysSettingMapper = sysSettingMapper;
        this.dataScopeService = dataScopeService;
    }

    // ---------------- 数据范围 ----------------

    /** 当前登录人的可见范围（单元 ∪ 本人负责指标，多角色取并集） */
    public ScopeFilter currentScope() {
        LoginUser user = SecurityUtils.getLoginUser();
        if (dataScopeService.isAllData(user)) {
            return ScopeFilter.allData();
        }
        Long unitId = dataScopeService.isUnitScope(user) ? user.getUnitId() : null;
        Set<Long> indicatorIds = dataScopeService.isOwnerScope(user)
                ? ownedIndicatorIds(user.getUserId()) : Set.of();
        return new ScopeFilter(false, unitId, indicatorIds);
    }

    /** 当前用户作为校级负责人显式负责的指标 id 集合 */
    private Set<Long> ownedIndicatorIds(Long userId) {
        return indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getOwnerId, userId)
                        .eq(Indicator::getDeleted, 0L)
                        .select(Indicator::getId))
                .stream().map(Indicator::getId).collect(Collectors.toSet());
    }

    /** 计划查询（导出等跨模块复用） */
    public Plan requirePlan(Long planId) {
        return planService.requirePlan(planId);
    }

    /** 指定负责人的数据范围（异步导出线程无 SecurityContext，创建任务时固化负责人 id） */
    public ScopeFilter scopeForOwner(Long ownerId) {
        return new ScopeFilter(false, null, ownedIndicatorIds(ownerId));
    }

    // ---------------- 年度解析 ----------------

    /** 解析统计年度：缺省取当前激活年度（无激活取最近锁定年度，再无取起始年）；超出起止 10001 */
    public Integer resolveYear(Plan plan, Integer year) {
        if (year != null) {
            if (year < plan.getStartYear() || year > plan.getEndYear()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "年度不在计划起止年份内");
            }
            return year;
        }
        List<PlanYear> years = planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                .eq(PlanYear::getPlanId, plan.getId())
                .eq(PlanYear::getDeleted, 0L));
        Integer resolved = years.stream().filter(y -> "ACTIVE".equals(y.getStatus()))
                .map(PlanYear::getYear).max(Integer::compareTo).orElse(null);
        if (resolved == null) {
            resolved = years.stream().filter(y -> "LOCKED".equals(y.getStatus()))
                    .map(PlanYear::getYear).max(Integer::compareTo).orElse(null);
        }
        return resolved == null ? plan.getStartYear() : resolved;
    }

    // ---------------- 总览 ----------------

    public StatsDtos.Overview overview(Long planId, Integer year) {
        return overview(planId, year, currentScope());
    }

    public StatsDtos.Overview overview(Long planId, Integer year, ScopeFilter scope) {
        Plan plan = planService.requirePlan(planId);
        int resolvedYear = resolveYear(plan, year);
        PlanContext ctx = loadContext(plan, resolvedYear, scope);

        List<TaskYear> countable = currentTaskYears(ctx).stream()
                .filter(ty -> StatsAggregator.countable(ty.getTarget())).toList();
        long achieved = countable.stream()
                .filter(ty -> StatsAggregator.isAchievedStatus(ty.getYearStatus())).count();
        long notAchieved = countable.stream()
                .filter(ty -> StatsAggregator.isNotAchievedStatus(ty.getYearStatus())).count();
        long inProgress = countable.stream()
                .filter(ty -> StatsAggregator.isInProgressStatus(ty.getYearStatus())).count();
        long makeupPending = countable.stream()
                .filter(ty -> "MAKEUP_PENDING".equals(ty.getYearStatus())).count();
        long madeUp = countable.stream()
                .filter(ty -> "MADE_UP".equals(ty.getYearStatus())).count();

        long constraintTotal = 0;
        long constraintAchieved = 0;
        long developmentTotal = 0;
        long developmentAchieved = 0;
        for (TaskYear ty : countable) {
            Indicator indicator = ctx.indicators().get(ty.getIndicatorId());
            String nature = indicator == null ? null : indicator.getNature();
            boolean ok = StatsAggregator.isAchievedStatus(ty.getYearStatus());
            if ("CONSTRAINT".equals(nature)) {
                constraintTotal++;
                if (ok) {
                    constraintAchieved++;
                }
            } else if ("DEVELOPMENT".equals(nature)) {
                developmentTotal++;
                if (ok) {
                    developmentAchieved++;
                }
            }
        }

        BigDecimal timeProgress = StatsAggregator.timeProgress(resolvedYear, LocalDate.now());
        List<StatsDtos.WarningItem> warnings = lagWarnings(ctx, resolvedYear, timeProgress);
        // 五年累计完成率：可见 A 指标累计完成率的算术平均（教材语义：五年总量才是 100%）
        BigDecimal cumulativeCompletionRate = CumulativeCalculator.avgRate(
                ctx.indicators().values().stream()
                        .filter(i -> i.getParentId() == null && Boolean.TRUE.equals(i.getIsQuantifiable()))
                        .filter(i -> indicatorVisible(i, scope, ctx))
                        .map(i -> cumulativeOfIndicator(i, ctx).rate())
                        .toList());
        return new StatsDtos.Overview(plan.getId(), resolvedYear, timeProgress,
                StatsAggregator.rate(achieved, countable.size()), cumulativeCompletionRate,
                constraintTotal, constraintAchieved, developmentTotal, developmentAchieved,
                countable.size(), achieved, notAchieved, inProgress, makeupPending, madeUp,
                warnings.size(), warnings);
    }

    /** 滞后预警：仅对已激活未锁定年度、进行中且有正目标的年度行判定 */
    private List<StatsDtos.WarningItem> lagWarnings(PlanContext ctx, int year, BigDecimal timeProgress) {
        PlanYear planYear = ctx.planYears().get(year);
        if (planYear == null || !"ACTIVE".equals(planYear.getStatus())) {
            return List.of();
        }
        BigDecimal threshold = lagThreshold();
        List<StatsDtos.WarningItem> warnings = new ArrayList<>();
        for (TaskYear ty : currentTaskYears(ctx)) {
            if (!StatsAggregator.countable(ty.getTarget())
                    || !StatsAggregator.isInProgressStatus(ty.getYearStatus())) {
                continue;
            }
            if (!StatsAggregator.isLagging(ty.getCompletionRate(), timeProgress, threshold)) {
                continue;
            }
            Task task = ctx.tasks().get(ty.getTaskId());
            Indicator indicator = ctx.indicators().get(ty.getIndicatorId());
            BigDecimal completion = ty.getCompletionRate() == null ? BigDecimal.ZERO : ty.getCompletionRate();
            warnings.add(new StatsDtos.WarningItem(ty.getTaskId(),
                    task == null ? null : task.getCode(),
                    indicator == null ? null : indicator.getName(),
                    unitRef(ctx.units().get(ty.getUnitId())),
                    year, ty.getCompletionRate(), timeProgress,
                    timeProgress.subtract(completion)));
        }
        warnings.sort(Comparator.comparing(StatsDtos.WarningItem::lagGap,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return warnings;
    }

    /** 滞后预警阈值（sys_setting，缺省 0.5） */
    public BigDecimal lagThreshold() {
        SysSetting setting = sysSettingMapper.selectOne(new LambdaQueryWrapper<SysSetting>()
                .eq(SysSetting::getSettingKey, LAG_THRESHOLD_KEY)
                .eq(SysSetting::getDeleted, 0L));
        if (setting == null) {
            return DEFAULT_LAG_THRESHOLD;
        }
        try {
            return new BigDecimal(setting.getSettingValue().trim());
        } catch (NumberFormatException e) {
            return DEFAULT_LAG_THRESHOLD;
        }
    }

    // ---------------- 按单元 / 按 A 级指标（全校聚合，PRD Q12） ----------------

    public List<StatsDtos.UnitStat> byUnit(Long planId, Integer year) {
        Plan plan = planService.requirePlan(planId);
        int resolvedYear = resolveYear(plan, year);
        PlanContext ctx = loadContext(plan, resolvedYear, ScopeFilter.allData());

        Map<Long, List<TaskYear>> byUnit = ctx.taskYears().stream()
                .filter(ty -> StatsAggregator.countable(ty.getTarget()))
                .collect(Collectors.groupingBy(TaskYear::getUnitId));
        List<StatsDtos.UnitStat> stats = new ArrayList<>();
        for (Map.Entry<Long, List<TaskYear>> entry : byUnit.entrySet()) {
            List<TaskYear> currentRows = entry.getValue().stream()
                    .filter(ty -> isCurrentTask(ty, ctx)).toList();
            if (currentRows.isEmpty()) {
                continue;
            }
            long achieved = currentRows.stream()
                    .filter(ty -> StatsAggregator.isAchievedStatus(ty.getYearStatus())).count();
            BigDecimal cumulativeRate = CumulativeCalculator.avgRate(entry.getValue().stream()
                    .map(ty -> cumulativeOfTask(ty, ctx).rate()).toList());
            stats.add(new StatsDtos.UnitStat(unitRef(ctx.units().get(entry.getKey())),
                    currentRows.size(), achieved,
                    StatsAggregator.rate(achieved, currentRows.size()), cumulativeRate, 0));
        }
        stats.sort(Comparator.comparing(StatsDtos.UnitStat::completionRate,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(s -> s.unit() == null ? "" : s.unit().code(),
                        Comparator.nullsLast(String::compareTo)));
        List<StatsDtos.UnitStat> ranked = new ArrayList<>(stats.size());
        for (int i = 0; i < stats.size(); i++) {
            StatsDtos.UnitStat s = stats.get(i);
            ranked.add(new StatsDtos.UnitStat(s.unit(), s.taskCount(), s.achievedCount(),
                    s.completionRate(), s.cumulativeRate(), i + 1));
        }
        return ranked;
    }

    public List<StatsDtos.IndicatorStat> byIndicator(Long planId, Integer year) {
        Plan plan = planService.requirePlan(planId);
        int resolvedYear = resolveYear(plan, year);
        PlanContext ctx = loadContext(plan, resolvedYear, ScopeFilter.allData());

        Map<Long, List<TaskYear>> taskYearsByA = new LinkedHashMap<>();
        for (TaskYear ty : ctx.taskYears()) {
            Task task = ctx.tasks().get(ty.getTaskId());
            if (task == null || task.getTopIndicatorId() == null) {
                continue;
            }
            taskYearsByA.computeIfAbsent(task.getTopIndicatorId(), k -> new ArrayList<>()).add(ty);
        }
        return ctx.indicators().values().stream()
                .filter(i -> i.getParentId() == null && Boolean.TRUE.equals(i.getIsQuantifiable()))
                .sorted(Comparator.comparing(Indicator::getSort).thenComparing(Indicator::getCode))
                .map(a -> {
                    List<TaskYear> allRows = taskYearsByA.getOrDefault(a.getId(), List.of()).stream()
                            .filter(ty -> StatsAggregator.countable(ty.getTarget())).toList();
                    List<TaskYear> currentRows = allRows.stream()
                            .filter(ty -> isCurrentTask(ty, ctx)).toList();
                    long achieved = currentRows.stream()
                            .filter(ty -> StatsAggregator.isAchievedStatus(ty.getYearStatus())).count();
                    BigDecimal cumulativeRate = CumulativeCalculator.avgRate(allRows.stream()
                            .map(ty -> cumulativeOfTask(ty, ctx).rate()).toList());
                    long aPlusCount = ctx.indicators().values().stream()
                            .filter(i -> a.getId().equals(i.getParentId())).count();
                    return new StatsDtos.IndicatorStat(a.getId(), a.getCode(), a.getName(), aPlusCount,
                            currentRows.size(), achieved, StatsAggregator.rate(achieved, currentRows.size()),
                            cumulativeRate);
                })
                .toList();
    }

    // ---------------- A → A+ → B 下钻树 ----------------

    public List<StatsDtos.TreeNode> tree(Long planId, Integer year) {
        return tree(planId, year, currentScope());
    }

    public List<StatsDtos.TreeNode> tree(Long planId, Integer year, ScopeFilter scope) {
        Plan plan = planService.requirePlan(planId);
        int resolvedYear = resolveYear(plan, year);
        PlanContext ctx = loadContext(plan, resolvedYear, scope);

        // 可见 B 按指标分组
        Map<Long, List<TaskYear>> taskYearsByIndicator = ctx.taskYears().stream()
                .collect(Collectors.groupingBy(TaskYear::getIndicatorId));

        List<Indicator> aList = ctx.indicators().values().stream()
                .filter(i -> i.getParentId() == null && Boolean.TRUE.equals(i.getIsQuantifiable()))
                .filter(i -> indicatorVisible(i, scope, ctx))
                .sorted(Comparator.comparing(Indicator::getSort).thenComparing(Indicator::getCode))
                .toList();

        List<StatsDtos.TreeNode> result = new ArrayList<>();
        for (Indicator a : aList) {
            List<Indicator> aPlusList = ctx.indicators().values().stream()
                    .filter(i -> a.getId().equals(i.getParentId()))
                    .filter(i -> indicatorVisible(i, scope, ctx))
                    .sorted(Comparator.comparing(Indicator::getSort).thenComparing(Indicator::getCode))
                    .toList();
            List<StatsDtos.TreeNode> children = new ArrayList<>();
            List<MetricCalculator.YearResult> aPlusResults = new ArrayList<>();
            for (Indicator ap : aPlusList) {
                List<TaskYear> apTaskYears = taskYearsByIndicator.getOrDefault(ap.getId(), List.of());
                MetricCalculator.YearResult apResult = nodeResult(ap, resolvedYear, plan, ctx, apTaskYears);
                aPlusResults.add(apResult);
                children.add(indicatorNode(ap, resolvedYear, ctx, apTaskYears, apResult));
            }
            result.add(aNode(a, resolvedYear, plan, ctx, children, aPlusResults,
                    taskYearsByIndicator.getOrDefault(a.getId(), List.of())));
        }
        return result;
    }

    /** 指标在范围内：全校 / 本人负责（含 A+ 沿用 A）/ 本单元在该指标下有可见任务 */
    private boolean indicatorVisible(Indicator indicator, ScopeFilter scope, PlanContext ctx) {
        if (scope.all()) {
            return true;
        }
        if (scope.indicatorIds().contains(indicator.getId())) {
            return true;
        }
        if (indicator.getParentId() != null && scope.indicatorIds().contains(indicator.getParentId())) {
            return true;
        }
        return ctx.tasks().values().stream()
                .anyMatch(t -> t.getIndicatorId().equals(indicator.getId())
                        || t.getTopIndicatorId().equals(indicator.getId()));
    }

    /** A 节点：AUTO_SUM 汇总下级 A+；MANUAL 用人工认定值；不可下分 A 汇总自身 B */
    private StatsDtos.TreeNode aNode(Indicator a, int year, Plan plan, PlanContext ctx,
                                     List<StatsDtos.TreeNode> aPlusChildren,
                                     List<MetricCalculator.YearResult> aPlusResults,
                                     List<TaskYear> ownTaskYears) {
        BigDecimal target = yearTarget(ctx, a.getId(), year);
        CumulativeCalculator.Cumulative cumulative = cumulativeOfIndicator(a, ctx);
        MetricCalculator.YearResult result;
        BigDecimal actualValue;
        BigDecimal completionRate;
        String achieveStatus;
        if (!ownTaskYears.isEmpty()) {
            // 体系改造前的 A 直承历史任务保留在详情和向上汇总中
            result = MetricCalculator.rollup(a.getStatCaliber(), toYearResults(ownTaskYears),
                    prevIndicatorTotal(ctx, a, year, plan));
            actualValue = result.actualValue();
            completionRate = MetricCalculator.completionRate(a.getStatCaliber(), result, target);
            achieveStatus = MetricCalculator.achieveStatus(a.getTargetDirection(), a.getStatCaliber(), result, target);
            List<StatsDtos.TreeNode> leaves = ownTaskYears.stream()
                    .map(ty -> taskLeaf(ty, ctx))
                    .sorted(Comparator.comparing(n -> n.unit() == null ? "" : n.unit().code(),
                            Comparator.nullsFirst(String::compareTo)))
                    .toList();
            return new StatsDtos.TreeNode(a.getId(), a.getCode(), a.getName(), "A", a.getNature(),
                    a.getStatCaliber(), a.getUnitLabel(), a.getTargetDirection(),
                    targetView(a.getStatCaliber(), target), actualValue, completionRate, achieveStatus,
                    cumulative.actual(), cumulative.rate(), null, null, null, null, null, leaves);
        }
        if ("MANUAL".equals(a.getRollupMode())) {
            // 手工认定：完成值取人工认定值（未认定时为空，不下钻汇总）
            IndicatorYearTarget row = yearTargetRow(ctx, a.getId(), year);
            actualValue = row == null ? null : row.getManualActualValue();
            MetricCalculator.YearResult manual = actualValue == null
                    ? MetricCalculator.YearResult.empty()
                    : new MetricCalculator.YearResult(actualValue, null, null, null, null, null);
            completionRate = MetricCalculator.completionRate(a.getStatCaliber(), manual, target);
            achieveStatus = MetricCalculator.achieveStatus(a.getTargetDirection(), a.getStatCaliber(), manual, target);
        } else {
            result = MetricCalculator.rollup(a.getStatCaliber(), aPlusResults,
                    prevIndicatorTotal(ctx, a, year, plan));
            actualValue = result.actualValue();
            completionRate = MetricCalculator.completionRate(a.getStatCaliber(), result, target);
            achieveStatus = MetricCalculator.achieveStatus(a.getTargetDirection(), a.getStatCaliber(), result, target);
        }
        return new StatsDtos.TreeNode(a.getId(), a.getCode(), a.getName(), "A", a.getNature(),
                a.getStatCaliber(), a.getUnitLabel(), a.getTargetDirection(),
                targetView(a.getStatCaliber(), target), actualValue, completionRate, achieveStatus,
                cumulative.actual(), cumulative.rate(), null, null, null, null, null, aPlusChildren);
    }

    /** A+ 节点：汇总可见 B 叶子 */
    private StatsDtos.TreeNode indicatorNode(Indicator indicator, int year, PlanContext ctx,
                                             List<TaskYear> taskYears, MetricCalculator.YearResult result) {
        BigDecimal target = yearTarget(ctx, indicator.getId(), year);
        CumulativeCalculator.Cumulative cumulative = cumulativeOfIndicator(indicator, ctx);
        List<StatsDtos.TreeNode> leaves = taskYears.stream()
                .map(ty -> taskLeaf(ty, ctx))
                .sorted(Comparator.comparing(n -> n.unit() == null ? "" : n.unit().code(),
                        Comparator.nullsFirst(String::compareTo)))
                .toList();
        return new StatsDtos.TreeNode(indicator.getId(), indicator.getCode(), indicator.getName(),
                "A_PLUS", indicator.getNature(), indicator.getStatCaliber(), indicator.getUnitLabel(),
                indicator.getTargetDirection(), targetView(indicator.getStatCaliber(), target),
                result.actualValue(),
                MetricCalculator.completionRate(indicator.getStatCaliber(), result, target),
                MetricCalculator.achieveStatus(indicator.getTargetDirection(), indicator.getStatCaliber(),
                        result, target),
                cumulative.actual(), cumulative.rate(), null, null, null, null, null, leaves);
    }

    private MetricCalculator.YearResult nodeResult(Indicator indicator, int year, Plan plan,
                                                   PlanContext ctx, List<TaskYear> taskYears) {
        return MetricCalculator.rollup(indicator.getStatCaliber(), toYearResults(taskYears),
                prevIndicatorTotal(ctx, indicator, year, plan));
    }

    private List<MetricCalculator.YearResult> toYearResults(List<TaskYear> taskYears) {
        return taskYears.stream()
                .map(ty -> new MetricCalculator.YearResult(ty.getActualValue(), ty.getActualNumerator(),
                        ty.getActualDenominator(), ty.getActualTotal(), null, null))
                .toList();
    }

    /** B 叶子节点 */
    private StatsDtos.TreeNode taskLeaf(TaskYear ty, PlanContext ctx) {
        Task task = ctx.tasks().get(ty.getTaskId());
        Indicator indicator = ctx.indicators().get(ty.getIndicatorId());
        String caliber = indicator == null ? null : indicator.getStatCaliber();
        CumulativeCalculator.Cumulative cumulative = cumulativeOfTask(ty, ctx);
        return new StatsDtos.TreeNode(ty.getId(), task == null ? null : task.getCode(),
                indicator == null ? null : indicator.getName(), "B",
                indicator == null ? null : indicator.getNature(), caliber,
                indicator == null ? null : indicator.getUnitLabel(),
                indicator == null ? null : indicator.getTargetDirection(),
                targetView(caliber, ty.getTarget()), ty.getActualValue(), ty.getCompletionRate(), null,
                cumulative.actual(), cumulative.rate(), ty.getTaskId(), unitRef(ctx.units().get(ty.getUnitId())),
                ty.getYearStatus(), ty.getIsAchieved(), task != null && Boolean.TRUE.equals(task.getIsHistorical()),
                List.of());
    }

    // ---------------- 指标趋势 ----------------

    public StatsDtos.TrendView trend(Long indicatorId) {
        Indicator indicator = indicatorMapper.selectOne(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getId, indicatorId)
                .eq(Indicator::getDeleted, 0L));
        if (indicator == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "指标不存在");
        }
        // 数据范围：全校 / 指标有效负责人；纯学院视角不开放指标级聚合（20005）
        LoginUser user = SecurityUtils.getLoginUser();
        if (!dataScopeService.isAllData(user)) {
            Long effectiveOwner = indicator.getOwnerId();
            if (effectiveOwner == null && indicator.getParentId() != null) {
                Indicator parent = indicatorMapper.selectOne(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getId, indicator.getParentId())
                        .eq(Indicator::getDeleted, 0L));
                effectiveOwner = parent == null ? null : parent.getOwnerId();
            }
            if (!dataScopeService.isOwnerScope(user) || effectiveOwner == null
                    || !effectiveOwner.equals(user.getUserId())) {
                throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
            }
        }
        Plan plan = planService.requirePlan(indicator.getPlanId());
        Map<Integer, IndicatorYearTarget> byYear = yearTargetMapper.selectList(
                        new LambdaQueryWrapper<IndicatorYearTarget>()
                                .eq(IndicatorYearTarget::getIndicatorId, indicatorId)
                                .eq(IndicatorYearTarget::getDeleted, 0L))
                .stream().collect(Collectors.toMap(IndicatorYearTarget::getYear, Function.identity()));
        boolean manual = "MANUAL".equals(indicator.getRollupMode());
        List<StatsDtos.TrendYear> years = new ArrayList<>();
        List<MetricCalculator.YearResult> yearResults = new ArrayList<>();
        for (int y = plan.getStartYear(); y <= plan.getEndYear(); y++) {
            IndicatorYearTarget row = byYear.get(y);
            BigDecimal actual = row == null ? null
                    : manual && row.getManualActualValue() != null
                    ? row.getManualActualValue() : row.getActualValue();
            years.add(new StatsDtos.TrendYear(y,
                    row == null ? null : targetView(indicator.getStatCaliber(), row.getTarget()),
                    actual,
                    row == null ? null : row.getActualTotal(),
                    row == null ? null : row.getCompletionRate(),
                    row == null ? null : row.getAchieveStatus()));
            yearResults.add(row == null ? MetricCalculator.YearResult.empty()
                    : new MetricCalculator.YearResult(actual, row.getActualNumerator(),
                            row.getActualDenominator(), row.getActualTotal(), null, null));
        }
        // 五年累计进度（与 tree / overview 同口径）
        CumulativeCalculator.Cumulative cumulative = CumulativeCalculator.of(
                indicator.getStatCaliber(), yearResults, indicator.getTotalTarget());
        return new StatsDtos.TrendView(indicator.getId(), indicator.getCode(), indicator.getName(),
                indicator.getStatCaliber(), indicator.getUnitLabel(), indicator.getBaselineValue(),
                indicator.getTotalTarget(), cumulative.actual(), cumulative.rate(), years);
    }

    // ---------------- 上下文装载 ----------------

    /** 某计划某年的统计上下文（指标 / S 级关联 / 单元 / 可见任务与其年度行 / 年度目标缓存 / 计划年度）；
     *  taskYears 为统计年度行；taskYearsByTask 为全部年份按任务分组（五年累计进度用） */
    public record PlanContext(Plan plan,
                              Map<Long, Indicator> indicators,
                              Map<Long, Direction> directions,
                              Map<Long, OrgUnit> units,
                              Map<Long, Task> tasks,
                              List<TaskYear> taskYears,
                              Map<Long, List<TaskYear>> taskYearsByTask,
                              Map<String, IndicatorYearTarget> yearTargets,
                              Map<Integer, PlanYear> planYears) {
    }

    public PlanContext loadContext(Plan plan, int year, ScopeFilter scope) {
        List<Indicator> indicators = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, plan.getId())
                .eq(Indicator::getDeleted, 0L));
        Map<Long, Indicator> indicatorMap = indicators.stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        Map<Long, Direction> directionMap = directionMapper.selectList(new LambdaQueryWrapper<Direction>()
                        .eq(Direction::getPlanId, plan.getId())
                        .eq(Direction::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Direction::getId, Function.identity()));
        Map<Long, OrgUnit> unitMap = orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
        // 统计只覆盖已下发的任务（分解矩阵草稿不计入）
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getPlanId, plan.getId())
                        .ne(Task::getStatus, "PENDING_DISTRIBUTION")
                        .eq(Task::getDeleted, 0L))
                .stream().filter(scope::test).toList();
        Map<Long, Task> taskMap = tasks.stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        List<TaskYear> taskYears = tasks.isEmpty() ? List.of()
                : taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                .in(TaskYear::getTaskId, tasks.stream().map(Task::getId).toList())
                .eq(TaskYear::getYear, year)
                .eq(TaskYear::getDeleted, 0L));
        // 全部年份（五年累计进度用；任务量级小，一次查全）
        Map<Long, List<TaskYear>> taskYearsByTask = tasks.isEmpty() ? Map.of()
                : taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .in(TaskYear::getTaskId, tasks.stream().map(Task::getId).toList())
                        .eq(TaskYear::getDeleted, 0L)
                        .orderByAsc(TaskYear::getYear))
                .stream().collect(Collectors.groupingBy(TaskYear::getTaskId));
        Map<String, IndicatorYearTarget> yearTargetMap = indicators.isEmpty() ? Map.of()
                : yearTargetMapper.selectList(new LambdaQueryWrapper<IndicatorYearTarget>()
                        .in(IndicatorYearTarget::getIndicatorId, indicators.stream().map(Indicator::getId).toList())
                        .eq(IndicatorYearTarget::getDeleted, 0L))
                .stream().collect(Collectors.toMap(
                        t -> t.getIndicatorId() + ":" + t.getYear(), Function.identity()));
        Map<Integer, PlanYear> planYearMap = planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                        .eq(PlanYear::getPlanId, plan.getId())
                        .eq(PlanYear::getDeleted, 0L))
                .stream().collect(Collectors.toMap(PlanYear::getYear, Function.identity()));
        return new PlanContext(plan, indicatorMap, directionMap, unitMap, taskMap, taskYears,
                taskYearsByTask, yearTargetMap, planYearMap);
    }

    // ---------------- 内部工具 ----------------

    private List<TaskYear> currentTaskYears(PlanContext ctx) {
        return ctx.taskYears().stream().filter(ty -> isCurrentTask(ty, ctx)).toList();
    }

    private boolean isCurrentTask(TaskYear taskYear, PlanContext ctx) {
        Task task = ctx.tasks().get(taskYear.getTaskId());
        return task != null && !Boolean.TRUE.equals(task.getIsHistorical());
    }

    /** 指标五年累计进度（AUTO_SUM 读各年缓存；MANUAL 的 A 优先取人工认定值） */
    private CumulativeCalculator.Cumulative cumulativeOfIndicator(Indicator indicator, PlanContext ctx) {
        boolean manual = "MANUAL".equals(indicator.getRollupMode());
        Plan plan = ctx.plan();
        List<MetricCalculator.YearResult> years = new ArrayList<>();
        for (int y = plan.getStartYear(); y <= plan.getEndYear(); y++) {
            IndicatorYearTarget row = yearTargetRow(ctx, indicator.getId(), y);
            if (row == null) {
                years.add(MetricCalculator.YearResult.empty());
                continue;
            }
            BigDecimal actual = manual && row.getManualActualValue() != null
                    ? row.getManualActualValue() : row.getActualValue();
            years.add(new MetricCalculator.YearResult(actual, row.getActualNumerator(),
                    row.getActualDenominator(), row.getActualTotal(), null, null));
        }
        return CumulativeCalculator.of(indicator.getStatCaliber(), years, indicator.getTotalTarget());
    }

    /** B 任务五年累计进度（基于该任务全部年份的 task_year 缓存） */
    private CumulativeCalculator.Cumulative cumulativeOfTask(TaskYear ty, PlanContext ctx) {
        Indicator indicator = ctx.indicators().get(ty.getIndicatorId());
        String caliber = indicator == null ? null : indicator.getStatCaliber();
        return CumulativeCalculator.ofTask(caliber,
                ctx.taskYearsByTask().getOrDefault(ty.getTaskId(), List.of(ty)));
    }

    private BigDecimal yearTarget(PlanContext ctx, Long indicatorId, int year) {
        IndicatorYearTarget row = yearTargetRow(ctx, indicatorId, year);
        return row == null ? null : row.getTarget();
    }

    private IndicatorYearTarget yearTargetRow(PlanContext ctx, Long indicatorId, int year) {
        return ctx.yearTargets().get(indicatorId + ":" + year);
    }

    /** 指标级上年完成总量（STOCK / GROWTH 汇总用；第一年用基线值） */
    private BigDecimal prevIndicatorTotal(PlanContext ctx, Indicator indicator, int year, Plan plan) {
        if (year <= plan.getStartYear()) {
            return indicator.getBaselineValue();
        }
        IndicatorYearTarget prev = yearTargetRow(ctx, indicator.getId(), year - 1);
        return prev == null ? null : prev.getActualTotal();
    }

    /** BOOLEAN 口径目标视图：1 → true，其余 → null（与任务列表契约一致） */
    static Object targetView(String caliber, BigDecimal target) {
        if ("BOOLEAN".equals(caliber)) {
            return target != null && target.compareTo(BigDecimal.ZERO) > 0 ? Boolean.TRUE : null;
        }
        return target;
    }

    private StatsDtos.UnitRef unitRef(OrgUnit unit) {
        return unit == null ? null : new StatsDtos.UnitRef(unit.getId(), unit.getCode(), unit.getName());
    }
}
