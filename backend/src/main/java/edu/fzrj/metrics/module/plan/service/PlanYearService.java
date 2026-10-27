package edu.fzrj.metrics.module.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计划年度管理（API 约定 15.4）：年度激活后，该年已下发任务的 task_year 才能填报。
 */
@Service
public class PlanYearService {

    private final PlanService planService;
    private final PlanYearMapper planYearMapper;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;

    public PlanYearService(PlanService planService, PlanYearMapper planYearMapper,
                           TaskMapper taskMapper, TaskYearMapper taskYearMapper) {
        this.planService = planService;
        this.planYearMapper = planYearMapper;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
    }

    /**
     * 激活年度：plan_year 置为 ACTIVE；该年所有已下发（DISTRIBUTED / IN_PROGRESS）任务的
     * task_year 由 NOT_ACTIVATED 置为 IN_PROGRESS。重复激活幂等（直接返回现状）。
     */
    @Transactional
    public PlanDtos.Detail activateYear(Long planId, Integer year, Integer version) {
        Plan plan = planService.requirePlan(planId);
        planService.requireNotArchived(plan);
        planService.requireVersion(plan.getVersion(), version);
        if (!"ACTIVE".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.PLAN_STATUS_INVALID, "计划未启动执行，不能激活年度");
        }
        if (year < plan.getStartYear() || year > plan.getEndYear()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "年度不在计划起止年份内");
        }
        PlanYear planYear = planYearMapper.selectOne(new LambdaQueryWrapper<PlanYear>()
                .eq(PlanYear::getPlanId, planId)
                .eq(PlanYear::getYear, year)
                .eq(PlanYear::getDeleted, 0L));
        if (planYear == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "计划年度不存在");
        }
        if ("LOCKED".equals(planYear.getStatus())) {
            throw new BizException(ErrorCode.YEAR_SETTLED);
        }
        if ("ACTIVE".equals(planYear.getStatus())) {
            return planService.detail(planId);
        }
        Long userId = SecurityUtils.getUserId();
        planYear.setStatus("ACTIVE");
        planYear.setActivatedAt(LocalDateTime.now());
        planYear.setActivatedBy(userId);
        planYear.setUpdatedBy(userId);
        planYearMapper.updateById(planYear);

        List<Long> distributedTaskIds = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getPlanId, planId)
                        .in(Task::getStatus, List.of("DISTRIBUTED", "IN_PROGRESS"))
                        .eq(Task::getIsHistorical, false)
                        .eq(Task::getDeleted, 0L)
                        .select(Task::getId))
                .stream().map(Task::getId).toList();
        if (!distributedTaskIds.isEmpty()) {
            taskYearMapper.update(null, new LambdaUpdateWrapper<TaskYear>()
                    .in(TaskYear::getTaskId, distributedTaskIds)
                    .eq(TaskYear::getYear, year)
                    .eq(TaskYear::getYearStatus, "NOT_ACTIVATED")
                    .eq(TaskYear::getDeleted, 0L)
                    .set(TaskYear::getYearStatus, "IN_PROGRESS")
                    .set(TaskYear::getUpdatedBy, userId));
        }
        return planService.detail(planId);
    }
}
