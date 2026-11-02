package edu.fzrj.metrics.module.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.task.service.TaskYearActivationService;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 计划年度管理（API 约定 15.4）：年度激活后，该年已下发任务的 task_year 才能填报。
 */
@Service
public class PlanYearService {

    private final PlanService planService;
    private final PlanYearMapper planYearMapper;
    private final TaskYearActivationService taskYearActivationService;

    public PlanYearService(PlanService planService, PlanYearMapper planYearMapper,
                           TaskYearActivationService taskYearActivationService) {
        this.planService = planService;
        this.planYearMapper = planYearMapper;
        this.taskYearActivationService = taskYearActivationService;
    }

    /**
     * 激活年度：plan_year 置为 ACTIVE；同步该计划已下发任务在激活年度的 task_year 状态。
     * 重复激活也执行一次同步，用于修复历史遗漏。
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
            taskYearActivationService.syncPlan(planId, SecurityUtils.getUserId());
            return planService.detail(planId);
        }
        Long userId = SecurityUtils.getUserId();
        planYear.setStatus("ACTIVE");
        planYear.setActivatedAt(LocalDateTime.now());
        planYear.setActivatedBy(userId);
        planYear.setUpdatedBy(userId);
        planYearMapper.updateById(planYear);

        taskYearActivationService.syncPlan(planId, userId);
        return planService.detail(planId);
    }
}
