package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanReadScopeService;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.OrgUnitAdminService;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.support.AllocationRules;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务下发（API 约定 15.4）：先复核指标完整性、40002、40003，再校验 50001、50002；
 * 全部通过后在一个事务内把 B 任务与指标置为 DISTRIBUTED，并给学院负责人生成站内通知。
 */
@Service
public class DistributeService {

    private final PlanService planService;
    private final IndicatorService indicatorService;
    private final IndicatorMapper indicatorMapper;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final TaskYearActivationService taskYearActivationService;
    private final OrgUnitMapper orgUnitMapper;
    private final OrgUnitAdminService orgUnitAdminService;
    private final NotificationMapper notificationMapper;
    private final DataScopeService dataScopeService;
    private final PlanReadScopeService planReadScopeService;

    public DistributeService(PlanService planService, IndicatorService indicatorService,
                             IndicatorMapper indicatorMapper, TaskMapper taskMapper,
                             TaskYearMapper taskYearMapper,
                             TaskYearActivationService taskYearActivationService,
                             OrgUnitMapper orgUnitMapper,
                             OrgUnitAdminService orgUnitAdminService, NotificationMapper notificationMapper,
                             DataScopeService dataScopeService, PlanReadScopeService planReadScopeService) {
        this.planService = planService;
        this.indicatorService = indicatorService;
        this.indicatorMapper = indicatorMapper;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.taskYearActivationService = taskYearActivationService;
        this.orgUnitMapper = orgUnitMapper;
        this.orgUnitAdminService = orgUnitAdminService;
        this.notificationMapper = notificationMapper;
        this.dataScopeService = dataScopeService;
        this.planReadScopeService = planReadScopeService;
    }

    /** 下发审计快照：记录任务在下发前后的状态与版本。 */
    public List<Task> auditSnapshot(Long planId) {
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getPlanId, planId)
                .eq(Task::getDeleted, 0L)
                .orderByAsc(Task::getId));
    }

    @Transactional
    public TaskDtos.DistributeResponse distribute(Long planId, TaskDtos.DistributeRequest request) {
        Plan plan = planService.requirePlan(planId);
        planReadScopeService.requireVisible(plan);
        planService.requireNotArchived(plan);
        planService.requireVersion(plan.getVersion(), request.getVersion());
        List<Indicator> all = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, planId)
                .eq(Indicator::getDeleted, 0L));
        Map<Long, Indicator> byId = all.stream().collect(Collectors.toMap(Indicator::getId, Function.identity()));
        List<Indicator> expanded = restrictToScope(
                expand(request.getIndicatorIds(), all, byId), request.getIndicatorIds(), byId);
        List<Indicator> distributable = expanded.stream()
                .filter(i -> "DRAFT".equals(i.getStatus()) || "FINALIZED".equals(i.getStatus())
                        || "DISTRIBUTED".equals(i.getStatus()))
                .toList();
        Long userId = SecurityUtils.getUserId();
        if (distributable.isEmpty()) {
            taskYearActivationService.syncPlan(planId, userId);
            return new TaskDtos.DistributeResponse(0, 0);
        }
        List<Long> distributableIds = distributable.stream().map(Indicator::getId).toList();
        List<Task> expandedTasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .in(Task::getIndicatorId, distributableIds)
                .eq(Task::getDeleted, 0L));
        Set<Long> indicatorsWithPending = expandedTasks.stream()
                .filter(t -> !Boolean.TRUE.equals(t.getIsHistorical()))
                .filter(t -> "PENDING_DISTRIBUTION".equals(t.getStatus()))
                .map(Task::getIndicatorId)
                .collect(Collectors.toSet());
        List<Indicator> candidates = distributable.stream()
                .filter(i -> !"DISTRIBUTED".equals(i.getStatus()) || indicatorsWithPending.contains(i.getId()))
                .toList();
        if (candidates.isEmpty()) {
            taskYearActivationService.syncPlan(planId, userId);
            return new TaskDtos.DistributeResponse(0, 0);
        }
        indicatorService.validateForDistribution(plan, all);

        List<Long> candidateIds = candidates.stream().map(Indicator::getId).toList();
        Map<Long, Map<Integer, BigDecimal>> indicatorTargets = indicatorService.loadTargets(candidateIds);
        List<Task> tasks = expandedTasks.stream()
                .filter(t -> candidateIds.contains(t.getIndicatorId()))
                .toList();
        Map<Long, List<Task>> tasksByIndicator = tasks.stream()
                .collect(Collectors.groupingBy(Task::getIndicatorId));
        Map<Long, List<TaskYear>> yearsByTask = taskYearsByTask(tasks.stream().map(Task::getId).toList());
        List<Integer> planYears = AllocationService.planYears(plan);

        List<FieldError> balanceErrors = new ArrayList<>();
        for (Indicator indicator : candidates) {
            Map<Integer, BigDecimal> allocated = new HashMap<>();
            for (Task task : tasksByIndicator.getOrDefault(indicator.getId(), List.of())) {
                for (TaskYear year : yearsByTask.getOrDefault(task.getId(), List.of())) {
                    if (year.getTarget() != null) {
                        allocated.merge(year.getYear(), year.getTarget(), BigDecimal::add);
                    }
                }
            }
            balanceErrors.addAll(AllocationRules.balanceErrors(indicator.getCode(), indicator.getStatCaliber(),
                    AllocationRules.buildSummary(planYears,
                            indicatorTargets.getOrDefault(indicator.getId(), Map.of()), allocated)));
        }
        if (!balanceErrors.isEmpty()) {
            throw new BizException(ErrorCode.ALLOCATION_SUM_INVALID,
                    ErrorCode.ALLOCATION_SUM_INVALID.getMessage() + "，不能下发", balanceErrors);
        }

        List<Task> pending = tasks.stream()
                .filter(t -> !Boolean.TRUE.equals(t.getIsHistorical()))
                .filter(t -> "PENDING_DISTRIBUTION".equals(t.getStatus()))
                .toList();
        List<String> missingOwner = pending.stream()
                .filter(t -> t.getOwnerId() == null)
                .map(Task::getCode).sorted().toList();
        if (!missingOwner.isEmpty()) {
            throw new BizException(ErrorCode.TASK_OWNER_MISSING,
                    ErrorCode.TASK_OWNER_MISSING.getMessage(),
                    AllocationRules.missingOwnerErrors(missingOwner));
        }
        pending.forEach(task -> orgUnitAdminService.requireBAssignable(task.getUnitId()));

        LocalDateTime now = LocalDateTime.now();
        Map<Long, OrgUnit> units = unitsOf(pending.stream().map(Task::getUnitId).toList());
        Map<Long, Indicator> candidateById = candidates.stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        for (Task task : pending) {
            task.setStatus("DISTRIBUTED");
            task.setDistributedAt(now);
            task.setDistributedBy(userId);
            task.setUpdatedBy(userId);
            taskMapper.updateById(task);
            notifyOwner(plan, task, candidateById.get(task.getIndicatorId()), units.get(task.getUnitId()));
        }
        activateTaskYears(plan, userId);
        for (Indicator indicator : candidates) {
            indicator.setStatus("DISTRIBUTED");
            indicator.setUpdatedBy(userId);
            indicatorService.updateIndicator(indicator);
        }
        // A+ 全部下发后，所属 A 同步置为已下发
        markParentsDistributed(candidates, byId, userId);
        return new TaskDtos.DistributeResponse(candidates.size(), pending.size());
    }

    // ---------------- 内部 ----------------

    /**
     * 下发后同步计划内所有已下发任务的年度状态：计划年度已激活的年份直接置为 IN_PROGRESS。
     * 整计划同步可顺带修复此前“先激活年度、后下发任务”遗留的未激活记录。
     */
    private void activateTaskYears(Plan plan, Long userId) {
        taskYearActivationService.syncPlan(plan.getId(), userId);
    }

    private List<Indicator> restrictToScope(List<Indicator> indicators, List<Long> requestedIds,
                                            Map<Long, Indicator> byId) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (dataScopeService.isAllData(user)) {
            return indicators;
        }
        if (!dataScopeService.isOwnerScope(user)) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        if (requestedIds != null && !requestedIds.isEmpty()) {
            requestedIds.forEach(id -> dataScopeService.canAccessIndicatorOwner(
                    indicatorService.effectiveOwnerOf(byId.get(id))));
            indicators.forEach(indicator -> dataScopeService.canAccessIndicatorOwner(
                    indicatorService.effectiveOwnerOf(indicator)));
            return indicators;
        }
        return indicators.stream()
                .filter(indicator -> user.getUserId().equals(indicatorService.effectiveOwnerOf(indicator)))
                .toList();
    }

    /** 纯 A 下发自身直承 B；legacy A 只展开旧 A+，避免父子重复下发。 */
    private List<Indicator> expand(List<Long> indicatorIds, List<Indicator> all, Map<Long, Indicator> byId) {
        if (indicatorIds == null || indicatorIds.isEmpty()) {
            return all.stream()
                    .filter(i -> AllocationRules.isAllocatable(i.getLevel()) || isPureDirectA(i, all))
                    .toList();
        }
        Map<Long, Indicator> result = new LinkedHashMap<>();
        for (Long id : indicatorIds) {
            Indicator indicator = byId.get(id);
            if (indicator == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "指标不存在或不属于本计划：" + id);
            }
            if ("A".equals(indicator.getLevel())) {
                List<Indicator> legacyChildren = all.stream()
                        .filter(i -> id.equals(i.getParentId()))
                        .filter(i -> AllocationRules.isAllocatable(i.getLevel()))
                        .toList();
                if (legacyChildren.isEmpty() && isPureDirectA(indicator, all)) {
                    result.put(indicator.getId(), indicator);
                } else {
                    legacyChildren.forEach(i -> result.put(i.getId(), i));
                }
            } else if (AllocationRules.isAllocatable(indicator.getLevel())) {
                result.put(indicator.getId(), indicator);
            }
        }
        return List.copyOf(result.values());
    }

    private boolean isPureDirectA(Indicator indicator, List<Indicator> all) {
        return "A".equals(indicator.getLevel())
                && Boolean.TRUE.equals(indicator.getIsQuantifiable())
                && all.stream().noneMatch(i -> indicator.getId().equals(i.getParentId())
                && AllocationRules.isAllocatable(i.getLevel()));
    }

    private void markParentsDistributed(List<Indicator> candidates, Map<Long, Indicator> byId, Long userId) {
        List<Indicator> all = List.copyOf(byId.values());
        candidates.stream().map(Indicator::getParentId).filter(java.util.Objects::nonNull).distinct()
                .forEach(parentId -> {
                    Indicator parent = byId.get(parentId);
                    if (parent == null || !("DRAFT".equals(parent.getStatus())
                            || "FINALIZED".equals(parent.getStatus()))) {
                        return;
                    }
                    boolean allDistributed = all.stream()
                            .filter(i -> parentId.equals(i.getParentId()))
                            .allMatch(i -> "DISTRIBUTED".equals(i.getStatus())
                                    || candidates.stream().anyMatch(c -> c.getId().equals(i.getId())));
                    if (allDistributed) {
                        parent.setStatus("DISTRIBUTED");
                        parent.setUpdatedBy(userId);
                        indicatorService.updateIndicator(parent);
                    }
                });
    }

    private void notifyOwner(Plan plan, Task task, Indicator indicator, OrgUnit unit) {
        if (task.getOwnerId() == null) {
            return;
        }
        Notification notification = new Notification();
        notification.setUserId(task.getOwnerId());
        notification.setType("TASK_DISTRIBUTED");
        notification.setTitle("新的学院任务下发：" + task.getCode());
        notification.setContent("【" + plan.getName() + "】" + (indicator == null ? "" : indicator.getName())
                + "（" + task.getCode() + "）已下发到 " + (unit == null ? "本单位" : unit.getName()) + "，请在年度激活后及时填报。");
        notification.setBizType("task");
        notification.setBizId(task.getId());
        notification.setFeishuStatus("PENDING");
        notification.setFeishuRetryCount(0);
        notificationMapper.insert(notification);
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
        List<Long> distinct = unitIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .in(OrgUnit::getId, distinct)
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
    }
}
