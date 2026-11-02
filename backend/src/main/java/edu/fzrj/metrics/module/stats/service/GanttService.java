package edu.fzrj.metrics.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.milestone.entity.Milestone;
import edu.fzrj.metrics.module.milestone.mapper.MilestoneMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanReadScopeService;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.stats.dto.GanttDtos;
import edu.fzrj.metrics.module.stats.service.support.CumulativeCalculator;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.dto.TaskDependencyDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskDependency;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskDependencyService;
import edu.fzrj.metrics.module.task.service.support.TaskScheduleGraph;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class GanttService {

    private final PlanService planService;
    private final PlanReadScopeService planReadScopeService;
    private final IndicatorMapper indicatorMapper;
    private final IndicatorYearTargetMapper yearTargetMapper;
    private final TaskYearMapper taskYearMapper;
    private final MilestoneMapper milestoneMapper;
    private final TaskDependencyService dependencyService;
    private final OrgUnitMapper orgUnitMapper;
    private final UserRefResolver userRefResolver;

    public GanttService(PlanService planService, PlanReadScopeService planReadScopeService,
                        IndicatorMapper indicatorMapper, IndicatorYearTargetMapper yearTargetMapper,
                        TaskYearMapper taskYearMapper, MilestoneMapper milestoneMapper,
                        TaskDependencyService dependencyService, OrgUnitMapper orgUnitMapper,
                        UserRefResolver userRefResolver) {
        this.planService = planService;
        this.planReadScopeService = planReadScopeService;
        this.indicatorMapper = indicatorMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.taskYearMapper = taskYearMapper;
        this.milestoneMapper = milestoneMapper;
        this.dependencyService = dependencyService;
        this.orgUnitMapper = orgUnitMapper;
        this.userRefResolver = userRefResolver;
    }

    public GanttDtos.View get(Long planId, Long indicatorId) {
        Plan plan = planService.requirePlan(planId);
        planReadScopeService.requireVisible(plan);
        Indicator requested = indicatorId == null ? null : requireIndicator(indicatorId);
        if (requested != null && !planId.equals(requested.getPlanId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指标不属于该计划");
        }

        List<Indicator> allIndicators = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, planId)
                .eq(Indicator::getDeleted, 0L));
        Map<Long, Indicator> indicators = allIndicators.stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        List<Task> scopedTasks = planReadScopeService.visibleTasks(planId);
        Set<Long> visibleIndicatorIds = planReadScopeService.visibleIndicatorIds(allIndicators, scopedTasks);

        Indicator selectedRoot = requested == null ? null : rootOf(requested, indicators);
        if (selectedRoot != null && !visibleIndicatorIds.contains(selectedRoot.getId())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        List<Indicator> roots = allIndicators.stream()
                .filter(indicator -> "A".equals(indicator.getLevel()))
                .filter(indicator -> selectedRoot == null || indicator.getId().equals(selectedRoot.getId()))
                .filter(indicator -> visibleIndicatorIds.contains(indicator.getId()))
                .sorted(indicatorOrder())
                .toList();
        Set<Long> rootIds = roots.stream().map(Indicator::getId).collect(Collectors.toSet());
        List<Task> tasks = scopedTasks.stream()
                .filter(task -> selectedRoot == null || selectedRoot.getId().equals(task.getTopIndicatorId()))
                .filter(task -> rootIds.contains(task.getTopIndicatorId()))
                .collect(Collectors.toMap(Task::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
        Set<Long> taskIds = tasks.stream().map(Task::getId).collect(Collectors.toSet());
        List<Integer> years = IntStream.rangeClosed(plan.getStartYear(), plan.getEndYear()).boxed().toList();

        Map<String, TaskYear> taskYearIndex = loadTaskYears(tasks);
        Map<String, IndicatorYearTarget> targetIndex = loadTargets(rootIds);
        Map<Long, OrgUnit> units = loadUnits(tasks);
        Map<Long, SysUser> users = userRefResolver.mapOf(loadOwnerIds(roots, tasks));
        Map<Long, List<Task>> tasksByA = tasks.stream()
                .collect(Collectors.groupingBy(Task::getTopIndicatorId, LinkedHashMap::new, Collectors.toList()));

        List<GanttDtos.Node> nodes = new ArrayList<>();
        Map<Long, TaskScheduleGraph.Node> scheduleNodes = new LinkedHashMap<>();
        for (Indicator root : roots) {
            List<Task> children = tasksByA.getOrDefault(root.getId(), List.of()).stream()
                    .sorted(taskOrder(units)).toList();
            nodes.add(indicatorNode(root, children, years, taskYearIndex, targetIndex, users));
            for (Task task : children) {
                Indicator taskIndicator = indicators.getOrDefault(task.getIndicatorId(), root);
                GanttDtos.Node node = taskNode(task, taskIndicator, years, taskYearIndex, units, users);
                nodes.add(node);
                scheduleNodes.put(task.getId(), new TaskScheduleGraph.Node(task.getId(), node.startYear(), node.endYear()));
            }
        }

        List<TaskDependency> allDependencies = dependencyService.findByPlan(planId);
        List<TaskDependency> visibleDependencies = allDependencies.stream()
                .filter(dependency -> taskIds.contains(dependency.getPredecessorTaskId())
                        && taskIds.contains(dependency.getSuccessorTaskId()))
                .toList();
        int omittedDependencies = allDependencies.size() - visibleDependencies.size();
        List<TaskScheduleGraph.Edge> edges = visibleDependencies.stream()
                .map(dependency -> new TaskScheduleGraph.Edge(dependency.getId(),
                        dependency.getPredecessorTaskId(), dependency.getSuccessorTaskId()))
                .toList();
        TaskScheduleGraph.Result graphResult = TaskScheduleGraph.analyze(
                scheduleNodes.values().stream().toList(), edges);

        List<TaskDependencyDtos.View> dependencyViews = visibleDependencies.stream()
                .map(TaskDependencyService::toView).toList();
        List<GanttDtos.MilestoneView> milestones = loadMilestones(planId, roots, tasks,
                allIndicators, visibleIndicatorIds);
        return new GanttDtos.View(
                new GanttDtos.PlanView(plan.getId(), plan.getName(), plan.getStartYear(), plan.getEndYear(), plan.getStatus()),
                years, nodes, milestones, dependencyViews,
                new GanttDtos.CriticalPath(graphResult.status(), graphResult.taskIds(), graphResult.dependencyIds()),
                new GanttDtos.GraphInfo(omittedDependencies > 0, omittedDependencies,
                        graphMessage(graphResult.status(), omittedDependencies)));
    }

    private GanttDtos.Node indicatorNode(Indicator root, List<Task> children, List<Integer> years,
                                         Map<String, TaskYear> taskYearIndex,
                                         Map<String, IndicatorYearTarget> targetIndex,
                                         Map<Long, SysUser> users) {
        List<GanttDtos.YearCell> cells = new ArrayList<>();
        List<MetricCalculator.YearResult> results = new ArrayList<>();
        Integer startYear = null;
        Integer endYear = null;
        boolean manual = "MANUAL".equals(root.getRollupMode());
        for (Integer year : years) {
            IndicatorYearTarget targetRow = targetIndex.get(root.getId() + ":" + year);
            MetricCalculator.YearResult result;
            if (root.getStatCaliber() == null) {
                result = MetricCalculator.YearResult.empty();
            } else if (manual) {
                BigDecimal actual = targetRow == null ? null : targetRow.getManualActualValue();
                result = actual == null ? MetricCalculator.YearResult.empty()
                        : new MetricCalculator.YearResult(actual, null, null, null, null, null);
            } else {
                List<MetricCalculator.YearResult> sources = children.stream()
                        .map(task -> toYearResult(taskYearIndex.get(task.getId() + ":" + year)))
                        .toList();
                BigDecimal previousTotal = year.equals(years.get(0)) ? root.getBaselineValue()
                        : results.get(results.size() - 1).total();
                result = MetricCalculator.rollup(root.getStatCaliber(), sources, previousTotal);
            }
            BigDecimal target = targetRow == null ? null : targetRow.getTarget();
            cells.add(new GanttDtos.YearCell(year, StatsService.targetView(root.getStatCaliber(), target),
                    result.actualValue(), result.total(), result.numerator(), result.denominator(),
                    root.getStatCaliber() == null ? null
                            : MetricCalculator.completionRate(root.getStatCaliber(), result, target),
                    root.getStatCaliber() == null ? null
                            : MetricCalculator.achieveStatus(root.getTargetDirection(), root.getStatCaliber(), result, target),
                    null, null));
            results.add(result);
            if (target != null || result.hasData()) {
                startYear = startYear == null ? year : Math.min(startYear, year);
                endYear = endYear == null ? year : Math.max(endYear, year);
            }
        }
        CumulativeCalculator.Cumulative cumulative = root.getStatCaliber() == null
                ? CumulativeCalculator.Cumulative.empty()
                : CumulativeCalculator.of(root.getStatCaliber(), results, root.getTotalTarget());
        return new GanttDtos.Node(root.getId(), null, "indicator", root.getCode(), root.getName(),
                root.getStatCaliber(), root.getUnitLabel(), null, ownerName(root.getOwnerId(), users),
                startYear, endYear, cumulative.rate(), false, root.getStatus(), cells);
    }

    private GanttDtos.Node taskNode(Task task, Indicator indicator, List<Integer> years,
                                    Map<String, TaskYear> taskYearIndex, Map<Long, OrgUnit> units,
                                    Map<Long, SysUser> users) {
        List<TaskYear> taskYears = years.stream()
                .map(year -> taskYearIndex.get(task.getId() + ":" + year))
                .filter(Objects::nonNull).toList();
        List<GanttDtos.YearCell> cells = new ArrayList<>();
        Integer startYear = null;
        Integer endYear = null;
        for (Integer year : years) {
            TaskYear row = taskYearIndex.get(task.getId() + ":" + year);
            if (row != null && (row.getTarget() != null || hasActual(row))) {
                startYear = startYear == null ? year : Math.min(startYear, year);
                endYear = endYear == null ? year : Math.max(endYear, year);
            }
            cells.add(new GanttDtos.YearCell(year,
                    row == null ? null : StatsService.targetView(indicator.getStatCaliber(), row.getTarget()),
                    row == null ? null : row.getActualValue(),
                    row == null ? null : row.getActualTotal(),
                    row == null ? null : row.getActualNumerator(),
                    row == null ? null : row.getActualDenominator(),
                    row == null ? null : row.getCompletionRate(),
                    row == null ? null : row.getYearStatus(),
                    row == null ? null : row.getTargetVersion(),
                    row == null ? null : row.getFrozenAmount()));
        }
        CumulativeCalculator.Cumulative cumulative = CumulativeCalculator.ofTask(indicator.getStatCaliber(), taskYears);
        OrgUnit unit = units.get(task.getUnitId());
        return new GanttDtos.Node(task.getId(), task.getTopIndicatorId(), "task", task.getCode(),
                indicator.getName(), indicator.getStatCaliber(), indicator.getUnitLabel(),
                unit == null ? null : unit.getName(), ownerName(task.getOwnerId(), users),
                startYear, endYear, cumulative.rate(), Boolean.TRUE.equals(task.getIsHistorical()),
                task.getStatus(), cells);
    }

    private List<GanttDtos.MilestoneView> loadMilestones(Long planId, List<Indicator> roots, List<Task> tasks,
                                                         List<Indicator> allIndicators, Set<Long> visibleIndicatorIds) {
        if (roots.isEmpty() && tasks.isEmpty()) {
            return List.of();
        }
        Set<Long> rootIds = roots.stream().map(Indicator::getId).collect(Collectors.toSet());
        Set<Long> taskIds = tasks.stream().map(Task::getId).collect(Collectors.toSet());
        Map<Long, Indicator> indicators = allIndicators.stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        return milestoneMapper.selectList(new LambdaQueryWrapper<Milestone>()
                        .eq(Milestone::getPlanId, planId)
                        .eq(Milestone::getDeleted, 0L)
                        .orderByAsc(Milestone::getDueDate).orderByAsc(Milestone::getId))
                .stream().map(milestone -> {
                    if (milestone.getTaskId() != null) {
                        return taskIds.contains(milestone.getTaskId()) ? milestoneView(milestone, null) : null;
                    }
                    Indicator indicator = indicators.get(milestone.getIndicatorId());
                    if (indicator == null || !visibleIndicatorIds.contains(indicator.getId())) {
                        return null;
                    }
                    Long rootId = "A".equals(indicator.getLevel()) ? indicator.getId() : indicator.getParentId();
                    return rootIds.contains(rootId) ? milestoneView(milestone, rootId) : null;
                }).filter(Objects::nonNull).toList();
    }

    private GanttDtos.MilestoneView milestoneView(Milestone milestone, Long rootIndicatorId) {
        return new GanttDtos.MilestoneView(milestone.getId(),
                milestone.getTaskId() == null ? rootIndicatorId : null, milestone.getTaskId(),
                milestone.getName(), milestone.getRelatedYear(), milestone.getDueDate(),
                effectiveStatus(milestone), milestone.getCompletedAt(), milestone.getVersion());
    }

    private String effectiveStatus(Milestone milestone) {
        if (("NOT_STARTED".equals(milestone.getStatus()) || "IN_PROGRESS".equals(milestone.getStatus()))
                && milestone.getDueDate() != null && milestone.getDueDate().isBefore(LocalDate.now())) {
            return "OVERDUE";
        }
        return milestone.getStatus();
    }

    private Map<String, TaskYear> loadTaskYears(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return Map.of();
        }
        return taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .in(TaskYear::getTaskId, tasks.stream().map(Task::getId).toList())
                        .eq(TaskYear::getDeleted, 0L))
                .stream().collect(Collectors.toMap(row -> row.getTaskId() + ":" + row.getYear(), Function.identity()));
    }

    private Map<String, IndicatorYearTarget> loadTargets(Set<Long> rootIds) {
        if (rootIds.isEmpty()) {
            return Map.of();
        }
        return yearTargetMapper.selectList(new LambdaQueryWrapper<IndicatorYearTarget>()
                        .in(IndicatorYearTarget::getIndicatorId, rootIds)
                        .eq(IndicatorYearTarget::getDeleted, 0L))
                .stream().collect(Collectors.toMap(row -> row.getIndicatorId() + ":" + row.getYear(), Function.identity()));
    }

    private Map<Long, OrgUnit> loadUnits(List<Task> tasks) {
        List<Long> unitIds = tasks.stream().map(Task::getUnitId).filter(Objects::nonNull).distinct().toList();
        if (unitIds.isEmpty()) {
            return Map.of();
        }
        return orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .in(OrgUnit::getId, unitIds)
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
    }

    private List<Long> loadOwnerIds(List<Indicator> roots, List<Task> tasks) {
        List<Long> ids = new ArrayList<>();
        roots.forEach(indicator -> ids.add(indicator.getOwnerId()));
        tasks.forEach(task -> ids.add(task.getOwnerId()));
        return ids;
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

    private Indicator rootOf(Indicator indicator, Map<Long, Indicator> indicators) {
        if ("A".equals(indicator.getLevel())) {
            return indicator;
        }
        if ("A_PLUS".equals(indicator.getLevel()) && indicator.getParentId() != null) {
            Indicator parent = indicators.get(indicator.getParentId());
            if (parent != null) {
                return parent;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "甘特图指标必须是 A 或历史 A+");
    }

    private MetricCalculator.YearResult toYearResult(TaskYear row) {
        return row == null ? MetricCalculator.YearResult.empty()
                : new MetricCalculator.YearResult(row.getActualValue(), row.getActualNumerator(),
                row.getActualDenominator(), row.getActualTotal(), null, null);
    }

    private boolean hasActual(TaskYear row) {
        return row.getActualValue() != null || row.getActualTotal() != null
                || row.getActualNumerator() != null || row.getActualDenominator() != null;
    }

    private String ownerName(Long ownerId, Map<Long, SysUser> users) {
        SysUser user = ownerId == null ? null : users.get(ownerId);
        return user == null ? null : user.getName();
    }

    private Comparator<Indicator> indicatorOrder() {
        return Comparator.comparing(Indicator::getSort, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(Indicator::getCode, Comparator.nullsLast(String::compareTo));
    }

    private Comparator<Task> taskOrder(Map<Long, OrgUnit> units) {
        return Comparator.comparing((Task task) -> {
            OrgUnit unit = units.get(task.getUnitId());
            return unit == null ? null : unit.getCode();
        }, Comparator.nullsLast(String::compareTo)).thenComparing(Task::getCode, Comparator.nullsLast(String::compareTo));
    }

    private String graphMessage(String status, int omittedDependencies) {
        String stateMessage = switch (status) {
            case TaskScheduleGraph.EMPTY -> "当前范围没有任务";
            case TaskScheduleGraph.NO_DEPENDENCIES -> "当前范围没有任务依赖";
            case TaskScheduleGraph.UNSCHEDULED -> "依赖任务存在未排期年度，无法计算关键路径";
            case TaskScheduleGraph.CYCLE_DETECTED -> "任务依赖存在环路，无法计算关键路径";
            case TaskScheduleGraph.YEAR_CONFLICT -> "任务依赖存在 FS 年度冲突，后续任务必须晚于前置任务完成年度";
            default -> null;
        };
        if (omittedDependencies == 0) {
            return stateMessage;
        }
        String scopeMessage = "已按数据范围或指标筛选省略 " + omittedDependencies + " 条依赖，关键路径仅按可见任务计算";
        return stateMessage == null ? scopeMessage : stateMessage + "；" + scopeMessage;
    }
}
