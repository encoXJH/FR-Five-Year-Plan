package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * task_year 年度状态统一维护：计划年度已激活时，已下发的非历史任务年度应为 IN_PROGRESS。
 * 下发、计划启动、年度激活、调整自动建任务等写入路径统一调用，避免“任务已下发但年度未激活”。
 */
@Service
public class TaskYearActivationService {

    private static final List<String> EXECUTING_TASK_STATUSES = List.of("DISTRIBUTED", "IN_PROGRESS");

    private final PlanYearMapper planYearMapper;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;

    public TaskYearActivationService(PlanYearMapper planYearMapper, TaskMapper taskMapper,
                                     TaskYearMapper taskYearMapper) {
        this.planYearMapper = planYearMapper;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
    }

    /** 同步整个计划：修复历史遗漏，并把本次下发涉及的年度一并激活。 */
    @Transactional
    public int syncPlan(Long planId, Long userId) {
        List<Integer> activeYears = activeYears(planId);
        if (activeYears.isEmpty()) {
            return 0;
        }
        List<Long> taskIds = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getPlanId, planId)
                        .in(Task::getStatus, EXECUTING_TASK_STATUSES)
                        .eq(Task::getIsHistorical, false)
                        .eq(Task::getDeleted, 0L)
                        .select(Task::getId))
                .stream().map(Task::getId).toList();
        return syncTaskYears(planId, taskIds, userId, activeYears);
    }

    /** 同步指定任务（如调整审批自动新建的接收任务）。 */
    @Transactional
    public int syncTasks(Long planId, List<Long> taskIds, Long userId) {
        return syncTaskYears(planId, taskIds, userId, activeYears(planId));
    }

    private int syncTaskYears(Long planId, List<Long> taskIds, Long userId, List<Integer> activeYears) {
        if (taskIds == null || taskIds.isEmpty() || activeYears.isEmpty()) {
            return 0;
        }
        return taskYearMapper.update(null, new LambdaUpdateWrapper<TaskYear>()
                .eq(TaskYear::getPlanId, planId)
                .in(TaskYear::getTaskId, taskIds)
                .in(TaskYear::getYear, activeYears)
                .eq(TaskYear::getYearStatus, "NOT_ACTIVATED")
                .eq(TaskYear::getDeleted, 0L)
                .set(TaskYear::getYearStatus, "IN_PROGRESS")
                .set(TaskYear::getUpdatedBy, userId));
    }

    private List<Integer> activeYears(Long planId) {
        return planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                        .eq(PlanYear::getPlanId, planId)
                        .eq(PlanYear::getStatus, "ACTIVE")
                        .eq(PlanYear::getDeleted, 0L)
                        .select(PlanYear::getYear))
                .stream().map(PlanYear::getYear).toList();
    }
}
