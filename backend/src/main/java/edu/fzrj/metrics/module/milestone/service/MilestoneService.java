package edu.fzrj.metrics.module.milestone.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.milestone.dto.MilestoneDtos;
import edu.fzrj.metrics.module.milestone.entity.Milestone;
import edu.fzrj.metrics.module.milestone.mapper.MilestoneMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 里程碑（API 约定 15.7、PRD 5.7）：挂在指标或任务上（二选一）。
 * OVERDUE 为展示层换算状态：dueDate 已过且仍为 NOT_STARTED / IN_PROGRESS 时按逾期展示，不落库。
 */
@Service
public class MilestoneService {

    private static final Set<String> STATUSES = Set.of(
            "NOT_STARTED", "IN_PROGRESS", "COMPLETED", "OVERDUE", "POSTPONED");
    private static final Set<String> CREATE_STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS");

    private final MilestoneMapper milestoneMapper;
    private final IndicatorService indicatorService;
    private final TaskService taskService;
    private final PlanService planService;
    private final DataScopeService dataScopeService;
    private final UserRefResolver userRefResolver;

    public MilestoneService(MilestoneMapper milestoneMapper, IndicatorService indicatorService,
                            TaskService taskService, PlanService planService,
                            DataScopeService dataScopeService, UserRefResolver userRefResolver) {
        this.milestoneMapper = milestoneMapper;
        this.indicatorService = indicatorService;
        this.taskService = taskService;
        this.planService = planService;
        this.dataScopeService = dataScopeService;
        this.userRefResolver = userRefResolver;
    }

    /** 按 indicatorId 或 taskId 查询（二选一） */
    public List<MilestoneDtos.View> list(Long indicatorId, Long taskId) {
        if ((indicatorId == null) == (taskId == null)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "indicatorId 与 taskId 必须且只能提供一个");
        }
        LambdaQueryWrapper<Milestone> wrapper = new LambdaQueryWrapper<Milestone>()
                .eq(Milestone::getDeleted, 0L)
                .orderByAsc(Milestone::getDueDate).orderByAsc(Milestone::getId);
        if (indicatorId != null) {
            Indicator indicator = indicatorService.requireIndicator(indicatorId);
            requireIndicatorScope(indicator);
            wrapper.eq(Milestone::getIndicatorId, indicatorId);
        } else {
            Task task = taskService.requireTask(taskId);
            taskService.requireAccess(task);
            wrapper.eq(Milestone::getTaskId, taskId);
        }
        List<Milestone> milestones = milestoneMapper.selectList(wrapper);
        return toViews(milestones);
    }

    @Transactional
    public MilestoneDtos.View create(MilestoneDtos.CreateRequest request) {
        Long planId;
        Long indicatorId = request.getIndicatorId();
        Long taskId = request.getTaskId();
        if ((indicatorId == null) == (taskId == null)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "indicatorId 与 taskId 必须且只能提供一个");
        }
        Plan plan;
        if (indicatorId != null) {
            Indicator indicator = indicatorService.requireIndicator(indicatorId);
            requireIndicatorEditAuthority();
            requireIndicatorScope(indicator);
            plan = planService.requirePlan(indicator.getPlanId());
            planId = indicator.getPlanId();
        } else {
            Task task = taskService.requireTask(taskId);
            taskService.requireAccess(task);
            taskService.requireCurrentTask(task);
            plan = planService.requirePlan(task.getPlanId());
            planId = task.getPlanId();
        }
        planService.requireNotArchived(plan);
        if (request.getRelatedYear() != null && (request.getRelatedYear() < plan.getStartYear()
                || request.getRelatedYear() > plan.getEndYear())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "关联年度不在计划起止年份内");
        }
        String status = request.getStatus() == null ? "NOT_STARTED" : request.getStatus();
        if (!CREATE_STATUSES.contains(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新建里程碑状态只能是 NOT_STARTED / IN_PROGRESS");
        }
        Long userId = SecurityUtils.getUserId();
        Milestone milestone = new Milestone();
        milestone.setPlanId(planId);
        milestone.setIndicatorId(indicatorId);
        milestone.setTaskId(taskId);
        milestone.setName(request.getName());
        milestone.setDueDate(request.getDueDate());
        milestone.setDeliverable(request.getDeliverable());
        milestone.setRelatedYear(request.getRelatedYear());
        milestone.setStatus(status);
        milestone.setCreatedBy(userId);
        milestone.setUpdatedBy(userId);
        milestoneMapper.insert(milestone);
        return toView(requireMilestone(milestone.getId()));
    }

    @Transactional
    public MilestoneDtos.View patch(Long milestoneId, PatchHelper patch) {
        Milestone milestone = requireMilestone(milestoneId);
        requireScope(milestone);
        if (milestone.getTaskId() != null) {
            taskService.requireCurrentTask(milestone.getTaskId());
        }
        int version = patch.versionRequired();
        if (!milestone.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + milestone.getVersion());
        }
        Plan plan = planService.requirePlan(milestone.getPlanId());
        planService.requireNotArchived(plan);
        if (patch.has("name")) {
            String name = patch.text("name");
            if (name.isBlank() || name.length() > 200) {
                throw new BizException(ErrorCode.PARAM_INVALID, "名称必填且不超过 200 字");
            }
            milestone.setName(name);
        }
        if (patch.has("dueDate")) {
            String text = patch.text("dueDate");
            try {
                milestone.setDueDate(LocalDate.parse(text));
            } catch (Exception e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "dueDate 格式应为 yyyy-MM-dd");
            }
        }
        if (patch.has("deliverable")) {
            milestone.setDeliverable(patch.isNull("deliverable") ? null : patch.text("deliverable"));
        }
        if (patch.has("relatedYear")) {
            Integer relatedYear = patch.isNull("relatedYear") ? null : patch.integer("relatedYear");
            if (relatedYear != null && (relatedYear < plan.getStartYear() || relatedYear > plan.getEndYear())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "关联年度不在计划起止年份内");
            }
            milestone.setRelatedYear(relatedYear);
        }
        if (patch.has("status")) {
            String status = patch.text("status");
            if (!STATUSES.contains(status)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "里程碑状态取值不合法：" + status);
            }
            String old = milestone.getStatus();
            milestone.setStatus(status);
            if ("COMPLETED".equals(status) && !"COMPLETED".equals(old)) {
                milestone.setCompletedAt(LocalDateTime.now());
            } else if (!"COMPLETED".equals(status) && "COMPLETED".equals(old)) {
                milestone.setCompletedAt(null);
            }
        }
        milestone.setUpdatedBy(SecurityUtils.getUserId());
        if (milestoneMapper.updateById(milestone) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + requireMilestone(milestoneId).getVersion());
        }
        return toView(requireMilestone(milestoneId));
    }

    @Transactional
    public void delete(Long milestoneId, Integer version) {
        Milestone milestone = requireMilestone(milestoneId);
        requireScope(milestone);
        if (milestone.getTaskId() != null) {
            taskService.requireCurrentTask(milestone.getTaskId());
        }
        if (version == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 version（乐观锁版本号）");
        }
        if (!milestone.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + milestone.getVersion());
        }
        milestoneMapper.update(null, new LambdaUpdateWrapper<Milestone>()
                .eq(Milestone::getId, milestoneId)
                .eq(Milestone::getDeleted, 0L)
                .set(Milestone::getDeleted, milestoneId)
                .set(Milestone::getUpdatedBy, SecurityUtils.getUserId()));
    }

    // ---------------- 内部 ----------------

    public Milestone requireMilestone(Long milestoneId) {
        Milestone milestone = milestoneMapper.selectOne(new LambdaQueryWrapper<Milestone>()
                .eq(Milestone::getId, milestoneId)
                .eq(Milestone::getDeleted, 0L));
        if (milestone == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "里程碑不存在");
        }
        return milestone;
    }

    /** 里程碑数据范围：指标级按指标负责人，任务级按任务数据范围 */
    private void requireScope(Milestone milestone) {
        if (milestone.getIndicatorId() != null) {
            Indicator indicator = indicatorService.requireIndicator(milestone.getIndicatorId());
            requireIndicatorScope(indicator);
        } else {
            taskService.requireAccess(taskService.requireTask(milestone.getTaskId()));
        }
    }

    /** 指标级里程碑写操作需要 indicator:edit 权限码（学院负责人只能维护本单元任务的里程碑） */
    private void requireIndicatorEditAuthority() {
        if (!SecurityUtils.getLoginUser().getPermissions().contains("indicator:edit")) {
            throw new BizException(ErrorCode.FORBIDDEN, "指标级里程碑需要 indicator:edit 权限");
        }
    }

    private void requireIndicatorScope(Indicator indicator) {
        dataScopeService.canAccessIndicatorOwner(indicatorService.effectiveOwnerOf(indicator));
    }

    private List<MilestoneDtos.View> toViews(List<Milestone> milestones) {
        if (milestones.isEmpty()) {
            return List.of();
        }
        Map<Long, SysUser> users = userRefResolver.mapOf(
                milestones.stream().map(Milestone::getCreatedBy).toList());
        return milestones.stream().map(m -> toView(m, users)).toList();
    }

    private MilestoneDtos.View toView(Milestone milestone) {
        Map<Long, SysUser> users = userRefResolver.mapOf(List.of(milestone.getCreatedBy()));
        return toView(milestone, users);
    }

    private MilestoneDtos.View toView(Milestone m, Map<Long, SysUser> users) {
        MilestoneDtos.IndicatorRef indicatorRef = null;
        if (m.getIndicatorId() != null) {
            Indicator indicator = indicatorService.requireIndicator(m.getIndicatorId());
            indicatorRef = new MilestoneDtos.IndicatorRef(indicator.getId(), indicator.getCode(), indicator.getName());
        }
        MilestoneDtos.TaskRef taskRef = null;
        if (m.getTaskId() != null) {
            Task task = taskService.requireTask(m.getTaskId());
            taskRef = new MilestoneDtos.TaskRef(task.getId(), task.getCode());
        }
        return new MilestoneDtos.View(m.getId(), m.getPlanId(), indicatorRef, taskRef, m.getName(),
                m.getDueDate(), m.getDeliverable(), m.getRelatedYear(), effectiveStatus(m),
                m.getCompletedAt(), userRefResolver.refOf(m.getCreatedBy(), users),
                m.getVersion(), m.getCreatedAt(), m.getUpdatedAt());
    }

    /** OVERDUE 展示换算：已过计划完成日期且仍处于进行中状态 */
    private String effectiveStatus(Milestone m) {
        if (("NOT_STARTED".equals(m.getStatus()) || "IN_PROGRESS".equals(m.getStatus()))
                && m.getDueDate() != null && m.getDueDate().isBefore(LocalDate.now())) {
            return "OVERDUE";
        }
        return m.getStatus();
    }
}
