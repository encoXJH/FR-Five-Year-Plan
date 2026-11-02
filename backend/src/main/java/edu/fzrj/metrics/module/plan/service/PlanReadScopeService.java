package edu.fzrj.metrics.module.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 计划及指标的读取数据范围：全校 / 本人负责指标 / 本单元任务，多角色取并集。 */
@Service
public class PlanReadScopeService {

    private final DataScopeService dataScopeService;
    private final IndicatorMapper indicatorMapper;
    private final TaskMapper taskMapper;

    public PlanReadScopeService(DataScopeService dataScopeService, IndicatorMapper indicatorMapper,
                                TaskMapper taskMapper) {
        this.dataScopeService = dataScopeService;
        this.indicatorMapper = indicatorMapper;
        this.taskMapper = taskMapper;
    }

    public List<Plan> filterVisiblePlans(List<Plan> plans) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (dataScopeService.isAllData(user)) {
            return plans;
        }
        Set<Long> visiblePlanIds = visiblePlanIds(user);
        return plans.stream().filter(plan -> visiblePlanIds.contains(plan.getId())).toList();
    }

    public void requireVisible(Plan plan) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (!dataScopeService.isAllData(user) && !visiblePlanIds(user).contains(plan.getId())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
    }

    /** 本计划内当前用户可见的 B：本单元任务与本人负责指标关联任务取并集。 */
    public List<Task> visibleTasks(Long planId) {
        LoginUser user = SecurityUtils.getLoginUser();
        LambdaQueryWrapper<Task> query = new LambdaQueryWrapper<Task>()
                .eq(Task::getPlanId, planId)
                .eq(Task::getDeleted, 0L);
        if (!dataScopeService.isAllData(user)) {
            Long unitId = dataScopeService.isUnitScope(user) ? user.getUnitId() : null;
            List<Long> ownedIndicatorIds = dataScopeService.isOwnerScope(user)
                    ? ownedIndicatorIds(user.getUserId()) : List.of();
            if (unitId == null && ownedIndicatorIds.isEmpty()) {
                return List.of();
            }
            query.and(wrapper -> {
                boolean hasUnit = unitId != null;
                if (hasUnit) {
                    wrapper.eq(Task::getUnitId, unitId);
                }
                if (!ownedIndicatorIds.isEmpty()) {
                    if (hasUnit) {
                        wrapper.or();
                    }
                    wrapper.in(Task::getIndicatorId, ownedIndicatorIds)
                            .or().in(Task::getTopIndicatorId, ownedIndicatorIds);
                }
            });
        }
        return taskMapper.selectList(query);
    }

    /** 可见指标种子补齐父 A；输入均为同一计划的数据。 */
    public Set<Long> visibleIndicatorIds(List<Indicator> all, List<Task> visibleTasks) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (dataScopeService.isAllData(user)) {
            return all.stream().map(Indicator::getId).collect(Collectors.toSet());
        }
        Map<Long, Indicator> byId = all.stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        Set<Long> visible = new HashSet<>();
        if (dataScopeService.isOwnerScope(user)) {
            for (Indicator indicator : all) {
                Long ownerId = indicator.getOwnerId();
                if (ownerId == null && indicator.getParentId() != null) {
                    Indicator parent = byId.get(indicator.getParentId());
                    ownerId = parent == null ? null : parent.getOwnerId();
                }
                if (Objects.equals(user.getUserId(), ownerId)) {
                    visible.add(indicator.getId());
                }
            }
        }
        for (Task task : visibleTasks) {
            if (task.getIndicatorId() != null) {
                visible.add(task.getIndicatorId());
            }
            if (task.getTopIndicatorId() != null) {
                visible.add(task.getTopIndicatorId());
            }
        }
        List<Long> pending = new ArrayList<>(visible);
        while (!pending.isEmpty()) {
            Long id = pending.remove(pending.size() - 1);
            Indicator indicator = byId.get(id);
            if (indicator != null && indicator.getParentId() != null
                    && byId.containsKey(indicator.getParentId()) && visible.add(indicator.getParentId())) {
                pending.add(indicator.getParentId());
            }
        }
        return visible;
    }

    private Set<Long> visiblePlanIds(LoginUser user) {
        Set<Long> planIds = new HashSet<>();
        if (dataScopeService.isOwnerScope(user)) {
            indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                            .eq(Indicator::getOwnerId, user.getUserId())
                            .eq(Indicator::getDeleted, 0L)
                            .select(Indicator::getPlanId))
                    .forEach(indicator -> planIds.add(indicator.getPlanId()));
        }
        if (dataScopeService.isUnitScope(user) && user.getUnitId() != null) {
            taskMapper.selectList(new LambdaQueryWrapper<Task>()
                            .eq(Task::getUnitId, user.getUnitId())
                            .eq(Task::getDeleted, 0L)
                            .select(Task::getPlanId))
                    .forEach(task -> planIds.add(task.getPlanId()));
        }
        return planIds;
    }

    private List<Long> ownedIndicatorIds(Long userId) {
        return indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getOwnerId, userId)
                        .eq(Indicator::getDeleted, 0L)
                        .select(Indicator::getId))
                .stream().map(Indicator::getId).toList();
    }
}
