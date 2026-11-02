package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.task.dto.TaskDependencyDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskDependency;
import edu.fzrj.metrics.module.task.mapper.TaskDependencyMapper;
import edu.fzrj.metrics.module.task.service.support.TaskScheduleGraph;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TaskDependencyService {

    private final TaskDependencyMapper dependencyMapper;
    private final PlanMapper planMapper;
    private final PlanService planService;
    private final TaskService taskService;

    public TaskDependencyService(TaskDependencyMapper dependencyMapper, PlanMapper planMapper,
                                 PlanService planService, TaskService taskService) {
        this.dependencyMapper = dependencyMapper;
        this.planMapper = planMapper;
        this.planService = planService;
        this.taskService = taskService;
    }

    @Transactional
    public TaskDependencyDtos.View create(Long planId, TaskDependencyDtos.CreateRequest request) {
        Plan plan = lockPlan(planId);
        planService.requireNotArchived(plan);
        String type = request.getType() == null ? "FS" : request.getType();
        if (!"FS".equals(type)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "依赖类型只支持 FS");
        }
        if (request.getPredecessorTaskId().equals(request.getSuccessorTaskId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "前置任务与后续任务不能相同");
        }
        Map<Long, Task> tasks = taskService.findByIds(
                        List.of(request.getPredecessorTaskId(), request.getSuccessorTaskId()))
                .stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        Task predecessor = requireTask(tasks, request.getPredecessorTaskId());
        Task successor = requireTask(tasks, request.getSuccessorTaskId());
        if (!planId.equals(predecessor.getPlanId()) || !planId.equals(successor.getPlanId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "前置任务与后续任务必须属于同一计划");
        }
        taskService.requireAccess(predecessor);
        taskService.requireAccess(successor);
        taskService.requireCurrentTask(predecessor);
        taskService.requireCurrentTask(successor);

        Long duplicateCount = dependencyMapper.selectCount(new LambdaQueryWrapper<TaskDependency>()
                .eq(TaskDependency::getPlanId, planId)
                .eq(TaskDependency::getPredecessorTaskId, predecessor.getId())
                .eq(TaskDependency::getSuccessorTaskId, successor.getId())
                .eq(TaskDependency::getDeleted, 0L));
        if (duplicateCount != null && duplicateCount > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务依赖已存在");
        }
        List<TaskScheduleGraph.Edge> edges = new ArrayList<>(activeEdges(planId));
        edges.add(new TaskScheduleGraph.Edge(null, predecessor.getId(), successor.getId()));
        if (TaskScheduleGraph.wouldCreateCycle(edges)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该任务依赖会形成环路");
        }

        Long userId = SecurityUtils.getUserId();
        TaskDependency dependency = new TaskDependency();
        dependency.setPlanId(planId);
        dependency.setPredecessorTaskId(predecessor.getId());
        dependency.setSuccessorTaskId(successor.getId());
        dependency.setDependencyType(type);
        dependency.setCreatedBy(userId);
        dependency.setUpdatedBy(userId);
        dependencyMapper.insert(dependency);
        return toView(requireDependency(dependency.getId()));
    }

    @Transactional
    public void delete(Long dependencyId, Integer version) {
        TaskDependency existing = requireDependency(dependencyId);
        Plan plan = lockPlan(existing.getPlanId());
        planService.requireNotArchived(plan);
        TaskDependency dependency = requireDependency(dependencyId);
        Task predecessor = taskService.requireTask(dependency.getPredecessorTaskId());
        Task successor = taskService.requireTask(dependency.getSuccessorTaskId());
        taskService.requireAccess(predecessor);
        taskService.requireAccess(successor);
        taskService.requireCurrentTask(predecessor);
        taskService.requireCurrentTask(successor);
        if (version == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 version（乐观锁版本号）");
        }
        if (!dependency.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + dependency.getVersion());
        }
        int updated = dependencyMapper.update(null, new LambdaUpdateWrapper<TaskDependency>()
                .eq(TaskDependency::getId, dependencyId)
                .eq(TaskDependency::getVersion, version)
                .eq(TaskDependency::getDeleted, 0L)
                .set(TaskDependency::getDeleted, dependencyId)
                .set(TaskDependency::getVersion, version + 1)
                .set(TaskDependency::getUpdatedBy, SecurityUtils.getUserId()));
        if (updated == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "任务依赖已被他人修改或删除");
        }
    }

    public List<TaskDependency> findByPlan(Long planId) {
        return dependencyMapper.selectList(new LambdaQueryWrapper<TaskDependency>()
                .eq(TaskDependency::getPlanId, planId)
                .eq(TaskDependency::getDeleted, 0L)
                .orderByAsc(TaskDependency::getId));
    }

    public static TaskDependencyDtos.View toView(TaskDependency dependency) {
        return new TaskDependencyDtos.View(dependency.getId(), dependency.getPredecessorTaskId(),
                dependency.getSuccessorTaskId(), dependency.getDependencyType(), dependency.getVersion());
    }

    private List<TaskScheduleGraph.Edge> activeEdges(Long planId) {
        return findByPlan(planId).stream()
                .map(item -> new TaskScheduleGraph.Edge(item.getId(), item.getPredecessorTaskId(),
                        item.getSuccessorTaskId()))
                .toList();
    }

    private Task requireTask(Map<Long, Task> tasks, Long id) {
        Task task = tasks.get(id);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return task;
    }

    private Plan lockPlan(Long planId) {
        Plan plan = planMapper.selectByIdForUpdate(planId);
        if (plan == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "计划不存在");
        }
        return plan;
    }

    private TaskDependency requireDependency(Long id) {
        TaskDependency dependency = dependencyMapper.selectOne(new LambdaQueryWrapper<TaskDependency>()
                .eq(TaskDependency::getId, id)
                .eq(TaskDependency::getDeleted, 0L));
        if (dependency == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务依赖不存在");
        }
        return dependency;
    }
}
