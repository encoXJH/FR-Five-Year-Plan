package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.indicator.service.support.IndicatorRules;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.support.AllocationRules;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分解矩阵（API 约定 15.4 / 16.3）：A+ 按「执行单元 × 年度」分解为 B。
 * 矩阵草稿即 task / task_year 行（status = PENDING_DISTRIBUTION），不另建草稿表。
 */
@Service
public class AllocationService {

    private final IndicatorService indicatorService;
    private final PlanService planService;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final OrgUnitAdminService orgUnitAdminService;
    private final UserRefResolver userRefResolver;
    private final DataScopeService dataScopeService;

    public AllocationService(IndicatorService indicatorService, PlanService planService,
                             TaskMapper taskMapper, TaskYearMapper taskYearMapper,
                             OrgUnitMapper orgUnitMapper, OrgUnitAdminService orgUnitAdminService,
                             UserRefResolver userRefResolver, DataScopeService dataScopeService) {
        this.indicatorService = indicatorService;
        this.planService = planService;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.orgUnitAdminService = orgUnitAdminService;
        this.userRefResolver = userRefResolver;
        this.dataScopeService = dataScopeService;
    }

    /** 获取分解矩阵（含未保存过的空 rows）+ 合计校验 */
    public TaskDtos.AllocationView getAllocation(Long indicatorId) {
        Indicator indicator = requireAllocatable(indicatorId);
        dataScopeService.canAccessIndicatorOwner(indicatorService.effectiveOwnerOf(indicator));
        return buildView(indicator);
    }

    /** 保存分解矩阵（整体替换；允许合计不一致存草稿，下发时才强制 50001） */
    @Transactional
    public TaskDtos.AllocationView putAllocation(Long indicatorId, TaskDtos.AllocationPutRequest request) {
        Indicator indicator = requireAllocatable(indicatorId);
        Plan plan = planService.requirePlan(indicator.getPlanId());
        planService.requireNotArchived(plan);
        if ("DISTRIBUTED".equals(indicator.getStatus())) {
            throw new BizException(ErrorCode.TASK_DISTRIBUTED, "指标已下发，不能修改分解矩阵");
        }
        dataScopeService.canAccessIndicatorOwner(indicatorService.effectiveOwnerOf(indicator));
        planService.requireVersion(indicator.getVersion(), request.getVersion());

        List<TaskDtos.AllocationRow> rows = request.getRows() == null ? List.of() : request.getRows();
        List<Integer> planYears = planYears(plan);
        Map<Long, Task> existingByUnit = tasksOf(indicator.getId()).stream()
                .collect(Collectors.toMap(Task::getUnitId, Function.identity()));

        Set<Long> seen = new java.util.HashSet<>();
        Map<Long, OrgUnit> units = new HashMap<>();
        Map<Long, Map<Integer, BigDecimal>> rowTargets = new LinkedHashMap<>();
        for (TaskDtos.AllocationRow row : rows) {
            if (!seen.add(row.getUnitId())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "同一执行单元在矩阵中只能出现一次");
            }
            Task existing = existingByUnit.get(row.getUnitId());
            boolean historical = existing != null && Boolean.TRUE.equals(existing.getIsHistorical());
            OrgUnit unit = historical ? orgUnitAdminService.requireUnit(row.getUnitId())
                    : orgUnitAdminService.requireBAssignable(row.getUnitId());
            units.put(unit.getId(), unit);
            if (!historical) {
                requireUserIfPresent(row.getOwnerId());
            }
            IndicatorRules.requirePlanYears(
                    row.getYearTargets().stream().map(TaskDtos.AllocationYearTargetItem::getYear).toList(),
                    plan.getStartYear(), plan.getEndYear());
            Map<Integer, BigDecimal> targets = new LinkedHashMap<>();
            for (TaskDtos.AllocationYearTargetItem item : row.getYearTargets()) {
                targets.put(item.getYear(), IndicatorRules.normalizeTarget(item.getTarget(), indicator.getStatCaliber()));
            }
            if ("BOOLEAN".equals(indicator.getStatCaliber())) {
                long marked = targets.values().stream()
                        .filter(v -> v != null && v.compareTo(BigDecimal.ONE) == 0).count();
                if (marked > 1) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "是否型任务每行至多指定一个计划完成年份");
                }
            }
            if (historical) {
                requireHistoricalRowUnchanged(existing, row.getOwnerId(), targets, planYears);
            }
            rowTargets.put(unit.getId(), targets);
        }

        Long userId = SecurityUtils.getUserId();
        for (Task task : existingByUnit.values()) {
            if (Boolean.TRUE.equals(task.getIsHistorical()) && !rowTargets.containsKey(task.getUnitId())) {
                throw new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY, "历史任务不能移出分解矩阵");
            }
        }

        // 软删移出矩阵的当前任务及其年度行
        for (Map.Entry<Long, Task> entry : existingByUnit.entrySet()) {
            if (!rowTargets.containsKey(entry.getKey())) {
                softDeleteTask(entry.getValue(), userId);
            }
        }
        // upsert 留下的与新增的任务；历史行完成只读校验后原样保留
        for (Map.Entry<Long, Map<Integer, BigDecimal>> entry : rowTargets.entrySet()) {
            Long unitId = entry.getKey();
            OrgUnit unit = units.get(unitId);
            TaskDtos.AllocationRow row = rows.stream()
                    .filter(r -> r.getUnitId().equals(unitId)).findFirst().orElseThrow();
            Task task = existingByUnit.get(unitId);
            if (task != null && Boolean.TRUE.equals(task.getIsHistorical())) {
                continue;
            }
            if (task == null) {
                task = new Task();
                task.setPlanId(plan.getId());
                task.setIndicatorId(indicator.getId());
                task.setTopIndicatorId(indicator.getParentId() == null ? indicator.getId() : indicator.getParentId());
                task.setUnitId(unitId);
                task.setCode(AllocationRules.taskCode(indicator.getCode(), unit.getCode()));
                task.setOwnerId(row.getOwnerId());
                task.setStatus("PENDING_DISTRIBUTION");
                task.setIsHistorical(false);
                task.setCreatedBy(userId);
                task.setUpdatedBy(userId);
                taskMapper.insert(task);
            } else if (!Objects.equals(task.getOwnerId(), row.getOwnerId())) {
                task.setOwnerId(row.getOwnerId());
                task.setUpdatedBy(userId);
                taskMapper.updateById(task);
            }
            upsertTaskYears(task, plan, entry.getValue(), userId);
        }

        // 保存矩阵视为指标的一次修改：推进乐观锁版本
        indicator.setUpdatedBy(userId);
        if (indicatorService.updateIndicator(indicator) == 0) {
            Indicator latest = indicatorService.requireIndicator(indicatorId);
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + latest.getVersion());
        }
        return buildView(indicatorService.requireIndicator(indicatorId));
    }

    // ---------------- 内部 ----------------

    private Indicator requireAllocatable(Long indicatorId) {
        Indicator indicator = indicatorService.requireIndicator(indicatorId);
        if (!AllocationRules.isAllocatable(indicator.getLevel())) {
            throw new BizException(ErrorCode.LEVEL_INVALID, "分解矩阵仅适用于 A+");
        }
        return indicator;
    }

    /** 当前矩阵视图：rows（已有 task/task_year）+ summary + isBalanced */
    public TaskDtos.AllocationView buildView(Indicator indicator) {
        Plan plan = planService.requirePlan(indicator.getPlanId());
        List<Integer> planYears = planYears(plan);
        List<Task> tasks = tasksOf(indicator.getId());
        Map<Long, List<TaskYear>> yearsByTask = taskYearsByTask(tasks.stream().map(Task::getId).toList());

        Map<Long, OrgUnit> units = unitsOf(tasks.stream().map(Task::getUnitId).toList());
        Map<Long, SysUser> users = userRefResolver.mapOf(tasks.stream().map(Task::getOwnerId).toList());

        Map<Integer, BigDecimal> allocated = new HashMap<>();
        List<TaskDtos.AllocationRowView> rows = new ArrayList<>();
        for (Task task : tasks) {
            List<TaskYear> years = yearsByTask.getOrDefault(task.getId(), List.of());
            // BOOLEAN 口径非完成年 target 为 null，不能用 Collectors.toMap（value 为 null 会 NPE）
            Map<Integer, BigDecimal> byYear = new HashMap<>();
            years.forEach(y -> byYear.put(y.getYear(), y.getTarget()));
            byYear.forEach((year, target) -> {
                if (target != null) {
                    allocated.merge(year, target, BigDecimal::add);
                }
            });
            OrgUnit unit = units.get(task.getUnitId());
            List<TaskDtos.YearTargetView> yearViews = planYears.stream()
                    .map(year -> new TaskDtos.YearTargetView(year,
                            toTargetView(indicator.getStatCaliber(), byYear.get(year))))
                    .toList();
            rows.add(new TaskDtos.AllocationRowView(task.getId(), task.getCode(), task.getStatus(),
                    task.getIsHistorical(),
                    unit == null ? null : new TaskDtos.UnitRef(unit.getId(), unit.getCode(), unit.getName()),
                    userRefResolver.refOf(task.getOwnerId(), users), yearViews));
        }
        Map<Integer, BigDecimal> indicatorTargets = indicatorService.loadTargets(List.of(indicator.getId()))
                .getOrDefault(indicator.getId(), Map.of());
        List<TaskDtos.SummaryItem> summary = AllocationRules.buildSummary(planYears, indicatorTargets, allocated);
        return new TaskDtos.AllocationView(indicator.getId(), indicator.getVersion(),
                AllocationRules.isBalanced(indicator.getStatCaliber(), summary), summary, rows);
    }

    private List<Task> tasksOf(Long indicatorId) {
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getIndicatorId, indicatorId)
                .eq(Task::getDeleted, 0L)
                .orderByAsc(Task::getCode));
    }

    private Map<Long, List<TaskYear>> taskYearsByTask(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .in(TaskYear::getTaskId, taskIds)
                        .eq(TaskYear::getDeleted, 0L))
                .stream().collect(Collectors.groupingBy(TaskYear::getTaskId));
    }

    private Map<Long, OrgUnit> unitsOf(List<Long> unitIds) {
        List<Long> distinct = unitIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .in(OrgUnit::getId, distinct)
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
    }

    private void requireHistoricalRowUnchanged(Task task, Long ownerId, Map<Integer, BigDecimal> targets,
                                               List<Integer> planYears) {
        if (!Objects.equals(task.getOwnerId(), ownerId)) {
            throw new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY, "历史任务的负责人不能修改");
        }
        Map<Integer, TaskYear> existing = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .eq(TaskYear::getTaskId, task.getId())
                        .eq(TaskYear::getDeleted, 0L))
                .stream().collect(Collectors.toMap(TaskYear::getYear, Function.identity()));
        for (Integer year : planYears) {
            TaskYear row = existing.get(year);
            BigDecimal oldTarget = row == null ? null : row.getTarget();
            BigDecimal newTarget = targets.get(year);
            if (oldTarget == null ? newTarget != null
                    : newTarget == null || oldTarget.compareTo(newTarget) != 0) {
                throw new BizException(ErrorCode.TASK_HISTORICAL_READ_ONLY, "历史任务的年度目标不能修改");
            }
        }
    }

    /** upsert 某任务的年度目标行：target 与 original_target 同步（下发后矩阵锁定，调整走 M6 不再回写 original） */
    private void upsertTaskYears(Task task, Plan plan, Map<Integer, BigDecimal> targets, Long userId) {
        Map<Integer, TaskYear> existing = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .eq(TaskYear::getTaskId, task.getId())
                        .eq(TaskYear::getDeleted, 0L))
                .stream().collect(Collectors.toMap(TaskYear::getYear, Function.identity()));
        for (int year = plan.getStartYear(); year <= plan.getEndYear(); year++) {
            BigDecimal target = targets.get(year);
            TaskYear row = existing.get(year);
            if (row == null) {
                row = new TaskYear();
                row.setTaskId(task.getId());
                row.setYear(year);
                row.setPlanId(task.getPlanId());
                row.setIndicatorId(task.getIndicatorId());
                row.setUnitId(task.getUnitId());
                row.setTarget(target);
                row.setOriginalTarget(target);
                row.setTargetVersion(1);
                row.setFrozenAmount(BigDecimal.ZERO);
                row.setYearStatus("NOT_ACTIVATED");
                row.setCreatedBy(userId);
                row.setUpdatedBy(userId);
                taskYearMapper.insert(row);
            } else if (row.getTarget() == null ? target != null
                    : target == null || row.getTarget().compareTo(target) != 0) {
                row.setTarget(target);
                row.setOriginalTarget(target);
                row.setUpdatedBy(userId);
                taskYearMapper.updateById(row);
            }
        }
    }

    private void softDeleteTask(Task task, Long userId) {
        taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                        .eq(TaskYear::getTaskId, task.getId())
                        .eq(TaskYear::getDeleted, 0L))
                .forEach(year -> {
                    year.setDeleted(year.getId());
                    year.setUpdatedBy(userId);
                    taskYearMapper.updateById(year);
                });
        task.setDeleted(task.getId());
        task.setUpdatedBy(userId);
        taskMapper.updateById(task);
    }

    private void requireUserIfPresent(Long userId) {
        if (userId != null && !userRefResolver.mapOf(List.of(userId)).containsKey(userId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "学院项目负责人不存在");
        }
    }

    static List<Integer> planYears(Plan plan) {
        List<Integer> years = new ArrayList<>();
        for (int year = plan.getStartYear(); year <= plan.getEndYear(); year++) {
            years.add(year);
        }
        return years;
    }

    /** BOOLEAN 口径：库存 1/NULL，接口出参 true/null */
    static Object toTargetView(String statCaliber, BigDecimal target) {
        if ("BOOLEAN".equals(statCaliber)) {
            return target != null && target.compareTo(BigDecimal.ONE) == 0 ? Boolean.TRUE : null;
        }
        return target;
    }
}
