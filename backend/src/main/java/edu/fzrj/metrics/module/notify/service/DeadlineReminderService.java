package edu.fzrj.metrics.module.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.notify.service.support.ReminderRules;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.system.entity.SysSetting;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.SysSettingMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 截止提醒定时任务（PRD FR-M11-03、FR-M11-02）：每天扫描「执行中计划 × 已激活年度」，
 * 距年度核算截止日（次年 settleDeadline）N 天内向相关学院负责人发站内提醒（DEADLINE_REMINDER）。
 * N 从 sys_setting 的 reminder.days.beforeDeadline 读取（V2 种子默认 7），每次运行实时读取，改参即生效。
 * 去重：同一 task_year 同一接收人只提醒一次（bizType=task_year, bizId=taskYearId 已存在则跳过）。
 */
@Slf4j
@Service
public class DeadlineReminderService {

    /** 提醒天数参数键（V2 种子；缺省 7） */
    public static final String DAYS_KEY = "reminder.days.beforeDeadline";
    private static final int DEFAULT_DAYS = 7;

    private final PlanMapper planMapper;
    private final PlanYearMapper planYearMapper;
    private final TaskYearMapper taskYearMapper;
    private final TaskMapper taskMapper;
    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final NotificationMapper notificationMapper;
    private final SysSettingMapper sysSettingMapper;

    @Value("${app.reminder.enabled:true}")
    private boolean enabled;

    public DeadlineReminderService(PlanMapper planMapper, PlanYearMapper planYearMapper,
                                   TaskYearMapper taskYearMapper, TaskMapper taskMapper,
                                   SysUserMapper sysUserMapper, UserRoleMapper userRoleMapper,
                                   NotificationMapper notificationMapper, SysSettingMapper sysSettingMapper) {
        this.planMapper = planMapper;
        this.planYearMapper = planYearMapper;
        this.taskYearMapper = taskYearMapper;
        this.taskMapper = taskMapper;
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.notificationMapper = notificationMapper;
        this.sysSettingMapper = sysSettingMapper;
    }

    /** 定时入口：cron 可配（app.reminder.cron），开关 app.reminder.enabled（便携 / 测试环境可关） */
    @Scheduled(cron = "${app.reminder.cron:0 30 8 * * ?}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            int sent = run(LocalDate.now());
            if (sent > 0) {
                log.info("截止提醒：本次发送 {} 条站内消息", sent);
            }
        } catch (Exception e) {
            log.error("截止提醒任务执行失败", e);
        }
    }

    /** 核心扫描逻辑（独立成方法便于测试与手工触发）；返回本次新发送的提醒条数 */
    public int run(LocalDate today) {
        int daysBefore = daysBefore();
        List<Plan> plans = planMapper.selectList(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getStatus, "ACTIVE")
                .eq(Plan::getDeleted, 0L));
        int sent = 0;
        for (Plan plan : plans) {
            List<PlanYear> activeYears = planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                    .eq(PlanYear::getPlanId, plan.getId())
                    .eq(PlanYear::getStatus, "ACTIVE")
                    .eq(PlanYear::getDeleted, 0L));
            for (PlanYear planYear : activeYears) {
                LocalDate deadline = ReminderRules.deadlineOf(planYear.getYear(), plan.getSettleDeadline());
                if (!ReminderRules.inRemindWindow(today, deadline, daysBefore)) {
                    continue;
                }
                sent += remindPlanYear(plan, planYear, today, deadline);
            }
        }
        return sent;
    }

    private int remindPlanYear(Plan plan, PlanYear planYear, LocalDate today, LocalDate deadline) {
        List<TaskYear> years = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                .eq(TaskYear::getPlanId, plan.getId())
                .eq(TaskYear::getYear, planYear.getYear())
                .eq(TaskYear::getDeleted, 0L)
                .in(TaskYear::getYearStatus, ReminderRules.REMINDABLE_YEAR_STATUS)
                .isNotNull(TaskYear::getTarget)
                .gt(TaskYear::getTarget, java.math.BigDecimal.ZERO));
        if (years.isEmpty()) {
            return 0;
        }
        Map<Long, Task> taskMap = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .in(Task::getId, years.stream().map(TaskYear::getTaskId).distinct().toList())
                        .eq(Task::getIsHistorical, false)
                        .eq(Task::getDeleted, 0L)
                        .in(Task::getStatus, ReminderRules.REMINDABLE_TASK_STATUS))
                .stream().collect(Collectors.toMap(Task::getId, Function.identity()));
        if (taskMap.isEmpty()) {
            return 0;
        }
        // 已提醒去重键：userId + taskYearId（本年内不重复提醒）
        Set<String> alreadySent = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getType, "DEADLINE_REMINDER")
                        .eq(Notification::getBizType, "task_year")
                        .in(Notification::getBizId, years.stream().map(TaskYear::getId).distinct().toList())
                        .select(Notification::getUserId, Notification::getBizId))
                .stream()
                .map(n -> n.getUserId() + ":" + n.getBizId())
                .collect(Collectors.toCollection(HashSet::new));

        long daysLeft = ReminderRules.daysRemaining(today, deadline);
        int sent = 0;
        for (TaskYear ty : years) {
            Task task = taskMap.get(ty.getTaskId());
            if (task == null) {
                continue;
            }
            for (Long recipient : recipientsOf(task)) {
                if (!alreadySent.add(recipient + ":" + ty.getId())) {
                    continue;
                }
                Notification notification = new Notification();
                notification.setUserId(recipient);
                notification.setType("DEADLINE_REMINDER");
                notification.setTitle("填报截止提醒：" + task.getCode());
                notification.setContent("【" + plan.getName() + "】" + task.getCode() + " " + planYear.getYear()
                        + " 年度填报将于 " + deadline + " 截止（剩 " + daysLeft + " 天），请及时填报。");
                notification.setBizType("task_year");
                notification.setBizId(ty.getId());
                notification.setFeishuStatus("PENDING");
                notification.setFeishuRetryCount(0);
                notificationMapper.insert(notification);
                sent++;
            }
        }
        return sent;
    }

    /** 提醒接收人：任务负责人优先；未设负责人时取该单元全部启用的学院负责人 */
    private List<Long> recipientsOf(Task task) {
        if (task.getOwnerId() != null) {
            return List.of(task.getOwnerId());
        }
        List<SysUser> unitUsers = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUnitId, task.getUnitId())
                .eq(SysUser::getIsEnabled, true)
                .eq(SysUser::getDeleted, 0L)
                .select(SysUser::getId));
        if (unitUsers.isEmpty()) {
            return List.of();
        }
        Set<Long> unitOwnerIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .in(UserRole::getUserId, unitUsers.stream().map(SysUser::getId).toList())
                        .eq(UserRole::getRole, "UNIT_OWNER")
                        .select(UserRole::getUserId))
                .stream().map(UserRole::getUserId).collect(Collectors.toSet());
        return unitUsers.stream().map(SysUser::getId).filter(unitOwnerIds::contains).toList();
    }

    /** 提醒天数：sys_setting 实时读取，非法或缺失回退默认 7 天 */
    public int daysBefore() {
        SysSetting setting = sysSettingMapper.selectOne(new LambdaQueryWrapper<SysSetting>()
                .eq(SysSetting::getSettingKey, DAYS_KEY)
                .eq(SysSetting::getDeleted, 0L));
        if (setting == null) {
            return DEFAULT_DAYS;
        }
        try {
            return Math.max(0, Integer.parseInt(setting.getSettingValue().trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_DAYS;
        }
    }
}
