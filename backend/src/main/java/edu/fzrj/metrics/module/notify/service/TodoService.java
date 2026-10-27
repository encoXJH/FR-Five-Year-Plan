package edu.fzrj.metrics.module.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentService;
import edu.fzrj.metrics.module.notify.dto.NotifyDtos;
import edu.fzrj.metrics.module.notify.service.support.TodoRules;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.ReviewService;
import edu.fzrj.metrics.module.system.service.SettingService;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 我的待办（API 约定 15.9、PRD FR-M11-01、数据库设计 7.9）：
 * 待填报 FILL（学院）/ 待审核 REVIEW（校级负责人、管理员）/ 待确认接收 ACCEPT（学院）/ 待审批 APPROVAL（调整各环节）。
 * 不建表，按当前用户角色与数据范围实时聚合。
 */
@Service
public class TodoService {

    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final PlanYearMapper planYearMapper;
    private final SubmissionMapper submissionMapper;
    private final ReviewService reviewService;
    private final AdjustmentService adjustmentService;
    private final SettingService settingService;

    public TodoService(TaskMapper taskMapper, TaskYearMapper taskYearMapper,
                       PlanYearMapper planYearMapper, SubmissionMapper submissionMapper,
                       ReviewService reviewService, AdjustmentService adjustmentService,
                       SettingService settingService) {
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.planYearMapper = planYearMapper;
        this.submissionMapper = submissionMapper;
        this.reviewService = reviewService;
        this.adjustmentService = adjustmentService;
        this.settingService = settingService;
    }

    /** 填报截止日（YYYY-MM-DD）：年度 + 系统参数 plan.fillDeadline（默认 12-31，界面设计规范 A8） */
    private String fillDeadlineOfYear(Integer year) {
        if (year == null) {
            return null;
        }
        String md = settingService.getValue("plan.fillDeadline", "12-31");
        if (md == null || !md.matches("\\d{2}-\\d{2}")) {
            md = "12-31";
        }
        return year + "-" + md;
    }

    public NotifyDtos.TodoView todos() {
        LoginUser user = SecurityUtils.getLoginUser();
        List<NotifyDtos.TodoItem> items = new ArrayList<>();
        items.addAll(fillTodos(user));
        items.addAll(reviewTodos(user));
        items.addAll(acceptTodos(user));
        items.addAll(approvalTodos(user));
        items.sort(Comparator.comparing(NotifyDtos.TodoItem::todoSince,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, Long> summary = new HashMap<>();
        for (NotifyDtos.TodoItem item : items) {
            summary.merge(item.type(), 1L, Long::sum);
        }
        return new NotifyDtos.TodoView(items, summary);
    }

    /**
     * 待填报（学院负责人，本单元）：进行中的任务年度 + 待补做；
     * 年度已激活但 task_year 尚未惰性激活（NOT_ACTIVATED）的也可填报，一并列出。
     */
    private List<NotifyDtos.TodoItem> fillTodos(LoginUser user) {
        if (!user.getRoles().contains("UNIT_OWNER") || user.getUnitId() == null) {
            return List.of();
        }
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getUnitId, user.getUnitId())
                .eq(Task::getIsHistorical, false)
                .eq(Task::getDeleted, 0L)
                .in(Task::getStatus, List.of("DISTRIBUTED", "IN_PROGRESS")));
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, Task> taskMap = tasks.stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        List<TaskYear> years = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                .in(TaskYear::getTaskId, taskMap.keySet())
                .eq(TaskYear::getDeleted, 0L)
                .in(TaskYear::getYearStatus, List.of("IN_PROGRESS", "MAKEUP_PENDING", "NOT_ACTIVATED")));
        if (years.isEmpty()) {
            return List.of();
        }
        // 年度激活情况：惰性激活的 NOT_ACTIVATED 行只有在 plan_year ACTIVE 时才可填报
        Set<String> activeYears = activePlanYearKeys(years);
        return years.stream()
                .filter(ty -> TodoRules.isFillableTaskYear(ty.getYearStatus(),
                        activeYears.contains(ty.getPlanId() + ":" + ty.getYear())))
                .map(ty -> {
                    Task task = taskMap.get(ty.getTaskId());
                    String detail = "MAKEUP_PENDING".equals(ty.getYearStatus())
                            ? ty.getYear() + " 年未达标待补做" : ty.getYear() + " 年待填报";
                    return new NotifyDtos.TodoItem("FILL", ty.getTaskId(),
                            task == null ? null : task.getCode(), detail, ty.getUpdatedAt(),
                            fillDeadlineOfYear(ty.getYear()));
                })
                .toList();
    }

    /** 待审核：与 /reviews/pending 同口径（全校角色全部；校级负责人 = 本人负责指标含下级 A+） */
    private List<NotifyDtos.TodoItem> reviewTodos(LoginUser user) {
        if (!user.getPermissions().contains("submission:review")) {
            return List.of();
        }
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<Submission>()
                .eq(Submission::getStatus, "PENDING_REVIEW")
                .eq(Submission::getDeleted, 0L)
                .orderByAsc(Submission::getSubmittedAt);
        boolean allData = user.getRoles().contains("ADMIN") || user.getRoles().contains("LEADER");
        if (!allData) {
            if (!user.getRoles().contains("SCHOOL_OWNER")) {
                return List.of();
            }
            List<Long> visible = reviewService.ownedIndicatorIdsWithChildren(user.getUserId());
            if (visible.isEmpty()) {
                return List.of();
            }
            wrapper.in(Submission::getIndicatorId, visible);
        }
        List<Submission> submissions = submissionMapper.selectList(wrapper);
        if (submissions.isEmpty()) {
            return List.of();
        }
        Map<Long, Task> taskMap = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .in(Task::getId, submissions.stream().map(Submission::getTaskId).distinct().toList())
                        .eq(Task::getIsHistorical, false)
                        .eq(Task::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        return submissions.stream()
                .filter(s -> taskMap.containsKey(s.getTaskId()))
                .map(s -> {
                    Task task = taskMap.get(s.getTaskId());
                    return new NotifyDtos.TodoItem("REVIEW", s.getId(),
                            task.getCode(),
                            s.getYear() + " 年填报待审核", s.getSubmittedAt(),
                            fillDeadlineOfYear(s.getYear()));
                })
                .toList();
    }

    /** 待确认接收（学院负责人，本单元）：已下发但未确认的任务 */
    private List<NotifyDtos.TodoItem> acceptTodos(LoginUser user) {
        if (!user.getRoles().contains("UNIT_OWNER") || user.getUnitId() == null) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getUnitId, user.getUnitId())
                        .eq(Task::getIsHistorical, false)
                        .eq(Task::getStatus, "DISTRIBUTED")
                        .isNull(Task::getAcceptedAt)
                        .eq(Task::getDeleted, 0L))
                .stream()
                .map(t -> new NotifyDtos.TodoItem("ACCEPT", t.getId(), t.getCode(),
                        "任务待确认接收", t.getDistributedAt(), null))
                .toList();
    }

    /** 待审批：调整申请当前环节的期望处理人（初审 / 接收确认 / 校领导审批） */
    private List<NotifyDtos.TodoItem> approvalTodos(LoginUser user) {
        List<Adjustment> adjustments = adjustmentService.todoAdjustments(user);
        if (adjustments.isEmpty()) {
            return List.of();
        }
        List<Long> taskIds = adjustments.stream().map(Adjustment::getTaskId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Task> taskMap = taskIds.isEmpty() ? Map.of()
                : taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .in(Task::getId, taskIds)
                        .eq(Task::getIsHistorical, false)
                        .eq(Task::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        return adjustments.stream()
                .filter(a -> a.getTaskId() == null || taskMap.containsKey(a.getTaskId()))
                .map(a -> {
                    Task task = a.getTaskId() == null ? null : taskMap.get(a.getTaskId());
                    return new NotifyDtos.TodoItem("APPROVAL", a.getId(),
                            task == null ? null : task.getCode(),
                            "调整审批待处理（" + a.getCurrentNode() + "）", a.getCreatedAt(), null);
                })
                .toList();
    }

    private Set<String> activePlanYearKeys(List<TaskYear> years) {
        List<Long> planIds = years.stream().map(TaskYear::getPlanId).distinct().toList();
        return planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                        .in(PlanYear::getPlanId, planIds)
                        .eq(PlanYear::getStatus, "ACTIVE")
                        .eq(PlanYear::getDeleted, 0L))
                .stream()
                .map(py -> py.getPlanId() + ":" + py.getYear())
                .collect(Collectors.toCollection(HashSet::new));
    }
}
