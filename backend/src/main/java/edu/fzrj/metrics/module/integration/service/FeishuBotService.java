package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentService;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.dto.FeishuDtos;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.notify.service.NotificationService;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.stats.service.StatsService;
import edu.fzrj.metrics.module.stats.service.support.StatsAggregator;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.ReviewService;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.service.SettingService;
import edu.fzrj.metrics.module.system.service.UserUnitService;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.service.TaskStaffService;
import edu.fzrj.metrics.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 双高管理平台机器人：进度提醒与校级每日待办汇总。 */
@Slf4j
@Service
public class FeishuBotService {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    public static final String BOT_SETTING_KEY = "feishu.botEnabled";
    public static final String LOG_CLEARED_AT_SETTING_KEY = "feishu.logClearedAt";
    private static final int DIGEST_PREVIEW_SIZE = 5;
    private static final Set<String> EXECUTABLE_TASK_STATUSES = Set.of("DISTRIBUTED", "IN_PROGRESS");

    private final FeishuConfig config;
    private final PlanMapper planMapper;
    private final StatsService statsService;
    private final NotificationService notificationService;
    private final AdjustmentService adjustmentService;
    private final TaskStaffService taskStaffService;
    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserUnitService userUnitService;
    private final SubmissionMapper submissionMapper;
    private final ReviewService reviewService;
    private final TaskMapper taskMapper;
    private final NotificationMapper notificationMapper;
    private final FeishuPushService feishuPushService;
    private final SettingService settingService;

    public FeishuBotService(FeishuConfig config, PlanMapper planMapper, StatsService statsService,
                            NotificationService notificationService, AdjustmentService adjustmentService,
                            TaskStaffService taskStaffService, SysUserMapper sysUserMapper,
                            UserRoleMapper userRoleMapper, UserUnitService userUnitService,
                            SubmissionMapper submissionMapper, ReviewService reviewService, TaskMapper taskMapper,
                            NotificationMapper notificationMapper, FeishuPushService feishuPushService,
                            SettingService settingService) {
        this.config = config;
        this.planMapper = planMapper;
        this.statsService = statsService;
        this.notificationService = notificationService;
        this.adjustmentService = adjustmentService;
        this.taskStaffService = taskStaffService;
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.userUnitService = userUnitService;
        this.submissionMapper = submissionMapper;
        this.reviewService = reviewService;
        this.taskMapper = taskMapper;
        this.notificationMapper = notificationMapper;
        this.feishuPushService = feishuPushService;
        this.settingService = settingService;
    }

    public record RunResult(int scanned, int created, int deduplicated) {
    }

    /** 数据库开关优先，缺行时回退环境变量；非法值按停用处理。 */
    public boolean isBotEnabled() {
        String value = settingService.getValue(BOT_SETTING_KEY, Boolean.toString(config.isBotEnabled()));
        return "true".equalsIgnoreCase(value);
    }

    @Scheduled(cron = "${app.feishu.bot-progress-cron:0 45 8 * * ?}", zone = "Asia/Shanghai")
    public void scheduledProgressReminders() {
        if (!isBotEnabled()) {
            return;
        }
        try {
            RunResult result = runProgressReminders();
            log.info("飞书机器人进度提醒完成 scanned={} created={} deduplicated={}",
                    result.scanned(), result.created(), result.deduplicated());
        } catch (Exception e) {
            log.error("飞书机器人进度提醒执行失败", e);
        }
    }

    @Scheduled(cron = "${app.feishu.bot-leader-digest-cron:0 0 9 * * ?}", zone = "Asia/Shanghai")
    public void scheduledLeaderDailyDigest() {
        if (!isBotEnabled()) {
            return;
        }
        try {
            RunResult result = runLeaderDailyDigest();
            log.info("飞书机器人校级汇总完成 scanned={} created={} deduplicated={}",
                    result.scanned(), result.created(), result.deduplicated());
        } catch (Exception e) {
            log.error("飞书机器人校级汇总执行失败", e);
        }
    }

    public RunResult runProgressReminders() {
        return runProgressAt(LocalDate.now(BUSINESS_ZONE));
    }

    RunResult runProgressAt(LocalDate today) {
        Counter counter = new Counter();
        int year = today.getYear();
        List<Plan> plans = planMapper.selectList(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getPlanType, "INDICATOR")
                .eq(Plan::getStatus, "ACTIVE")
                .eq(Plan::getDeleted, 0L)
                .le(Plan::getStartYear, year)
                .ge(Plan::getEndYear, year));
        BigDecimal timeProgress = StatsAggregator.timeProgress(year, today);
        BigDecimal threshold = statsService.lagThreshold();
        for (Plan plan : plans) {
            StatsService.PlanContext ctx = statsService.loadContext(plan, year, StatsService.ScopeFilter.allData());
            PlanYear planYear = ctx.planYears().get(year);
            if (planYear == null || !"ACTIVE".equals(planYear.getStatus())) {
                continue;
            }
            for (TaskYear taskYear : ctx.taskYears()) {
                Task task = ctx.tasks().get(taskYear.getTaskId());
                if (task == null || Boolean.TRUE.equals(task.getIsHistorical())
                        || task.getStatus() == null || !EXECUTABLE_TASK_STATUSES.contains(task.getStatus())
                        || "NOT_ACTIVATED".equals(taskYear.getYearStatus())
                        || !StatsAggregator.countable(taskYear.getTarget())) {
                    continue;
                }
                if (StatsAggregator.isInProgressStatus(taskYear.getYearStatus())
                        && StatsAggregator.isLagging(taskYear.getCompletionRate(), timeProgress, threshold)) {
                    counter.scanned++;
                    notifyLag(plan, ctx, task, taskYear, today, counter);
                }
                if (hasRecognizedValue(taskYear) && Boolean.FALSE.equals(taskYear.getIsAchieved())
                        && (StatsAggregator.isInProgressStatus(taskYear.getYearStatus())
                        || "NOT_ACHIEVED".equals(taskYear.getYearStatus()))) {
                    counter.scanned++;
                    notifyYearGap(plan, ctx, task, taskYear, today, counter);
                }
            }
        }
        return counter.result();
    }

    public RunResult runLeaderDailyDigest() {
        return runLeaderDigestAt(LocalDate.now(BUSINESS_ZONE));
    }

    RunResult runLeaderDigestAt(LocalDate today) {
        Counter counter = new Counter();
        List<SysUser> leaders = activeUsersWithRole("LEADER");
        if (leaders.isEmpty()) {
            return counter.result();
        }
        Map<Long, List<String>> roles = rolesOf(leaders.stream().map(SysUser::getId).toList());
        Map<Long, List<Long>> units = userUnitService.unitIdsOf(leaders.stream().map(SysUser::getId).toList());
        for (SysUser leader : leaders) {
            LoginUser loginUser = new LoginUser(leader.getId(), leader.getUsername(), leader.getName(),
                    leader.getUnitId(), roles.getOrDefault(leader.getId(), List.of("LEADER")), List.of(),
                    units.getOrDefault(leader.getId(), List.of()));
            List<Submission> reviews = leaderReviewTodos(loginUser);
            counter.scanned += reviews.size();
            if (!reviews.isEmpty()) {
                String content = reviewDigestContent(reviews);
                counter.add(notificationService.insertOnce(notification(leader.getId(), "REVIEW_TODO",
                        "校级审核待办：" + reviews.size() + " 项", content, "feishu_bot", null,
                        "/reviews?reviewNode=LEADER_REVIEW",
                        "BOT:REVIEW_DAILY:" + leader.getId() + ":" + today)));
            }
            List<Adjustment> adjustments = adjustmentService.todoAdjustments(loginUser).stream()
                    .filter(a -> "LEADER_APPROVAL".equals(a.getCurrentNode())).toList();
            counter.scanned += adjustments.size();
            if (!adjustments.isEmpty()) {
                String content = adjustmentDigestContent(adjustments);
                counter.add(notificationService.insertOnce(notification(leader.getId(), "ADJUSTMENT_TODO",
                        "调整审批待办：" + adjustments.size() + " 项", content, "feishu_bot", null,
                        "/adjustments?scope=TODO",
                        "BOT:ADJUSTMENT_DAILY:" + leader.getId() + ":" + today)));
            }
        }
        return counter.result();
    }

    public FeishuDtos.BotStatusView status() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        Map<String, Long> todayPush = new LinkedHashMap<>();
        for (String pushStatus : List.of("PENDING", "SENT", "FAILED", "SKIPPED")) {
            todayPush.put(pushStatus, notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                    .eq(Notification::getFeishuStatus, pushStatus)
                    .ge(Notification::getCreatedAt, start)
                    .lt(Notification::getCreatedAt, end)));
        }
        List<FeishuDtos.BotRuleView> rules = List.of(
                new FeishuDtos.BotRuleView("TASK_STAFF_ASSIGNED", "干事任务分配",
                        "学院任务下发不通知学院负责人；仅新增任务分配时通知对应干事"),
                new FeishuDtos.BotRuleView("TASK_LAG", "任务滞后提醒",
                        "进度扫描发现滞后任务时，提醒所属学院全部启用学院负责人"),
                new FeishuDtos.BotRuleView("YEAR_GAP", "年度进度未达标",
                        "已有认定完成值但未达年度目标时，提醒任务负责人及当前分配干事"),
                new FeishuDtos.BotRuleView("REVIEW_RESULT", "审核结果通知",
                        "保留提交人通知；干事终审通过时追加通知所属学院启用学院负责人"),
                new FeishuDtos.BotRuleView("REVIEW_DAILY", "校级审核每日汇总",
                        "每天按校级负责人汇总当前待校级终审填报，无待办不发送"),
                new FeishuDtos.BotRuleView("ADJUSTMENT_DAILY", "调整审批每日汇总",
                        "每天按校级负责人汇总当前待校级审批调整，无待办不发送"));
        return new FeishuDtos.BotStatusView(config.isConfigured(), feishuPushService.isPushEnabled(), isBotEnabled(),
                BUSINESS_ZONE.getId(), config.getBotProgressCron(), config.getBotLeaderDigestCron(), rules, todayPush);
    }

    public PageResult<FeishuDtos.BotLogItem> logs(FeishuDtos.BotLogQuery query) {
        LocalDateTime start = query.getCreatedDate() == null ? null : query.getCreatedDate().atStartOfDay();
        LocalDateTime end = query.getCreatedDate() == null ? null : query.getCreatedDate().plusDays(1).atStartOfDay();
        String clearedAtValue = settingService.getValue(LOG_CLEARED_AT_SETTING_KEY, "");
        LocalDateTime clearedAt = null;
        if (clearedAtValue != null && !clearedAtValue.isBlank()) {
            try {
                clearedAt = LocalDateTime.parse(clearedAtValue.trim().replace(' ', 'T'));
            } catch (java.time.format.DateTimeParseException e) {
                log.warn("推送日志清空时点格式无效，按未设置处理：{}", clearedAtValue);
            }
        }
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .gt(clearedAt != null, Notification::getCreatedAt, clearedAt)
                .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                        Notification::getFeishuStatus, query.getStatus())
                .eq(query.getType() != null && !query.getType().isBlank(), Notification::getType, query.getType())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(), w -> w
                        .like(Notification::getTitle, query.getKeyword().trim())
                        .or().like(Notification::getContent, query.getKeyword().trim())
                        .or().like(Notification::getFeishuLastError, query.getKeyword().trim()))
                .ge(start != null, Notification::getCreatedAt, start)
                .lt(end != null, Notification::getCreatedAt, end)
                .orderByDesc(Notification::getCreatedAt)
                .orderByDesc(Notification::getId);
        Page<Notification> page = notificationMapper.selectPage(
                query.toPage(Set.of("created_at", "id")), wrapper);
        List<Long> userIds = page.getRecords().stream().map(Notification::getUserId).distinct().toList();
        Map<Long, String> names = userIds.isEmpty() ? Map.of() : sysUserMapper.selectList(
                        new LambdaQueryWrapper<SysUser>().in(SysUser::getId, userIds)
                                .select(SysUser::getId, SysUser::getName))
                .stream().collect(Collectors.toMap(SysUser::getId, SysUser::getName));
        List<FeishuDtos.BotLogItem> items = page.getRecords().stream()
                .map(n -> new FeishuDtos.BotLogItem(n.getId(), n.getUserId(), names.get(n.getUserId()),
                        n.getType(), n.getTitle(), n.getContent(), n.getLink(), n.getFeishuStatus(),
                        n.getFeishuRetryCount(), n.getFeishuLastError(), n.getCreatedAt()))
                .toList();
        return new PageResult<>(items, page.getTotal(), query.getPage(), query.getPageSize());
    }

    public RunResult runJob(String job) {
        if (job == null || job.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "job 不能为空");
        }
        return switch (job) {
            case "PROGRESS" -> runProgressReminders();
            case "LEADER_DIGEST" -> runLeaderDailyDigest();
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "job 仅支持 PROGRESS / LEADER_DIGEST");
        };
    }

    private void notifyLag(Plan plan, StatsService.PlanContext ctx, Task task, TaskYear taskYear,
                           LocalDate today, Counter counter) {
        List<Long> recipients = adjustmentService.unitOwnerIds(task.getUnitId());
        String content = progressContent(plan, ctx, task, taskYear,
                "任务已滞后、尚未完成，请及时督促办理。");
        for (Long recipient : recipients) {
            counter.add(notificationService.insertOnce(notification(recipient, "WARNING",
                    "任务滞后提醒：" + task.getCode(), content, "task_year", taskYear.getId(),
                    taskLink(taskYear),
                    "BOT:LAG:" + taskYear.getId() + ":" + recipient + ":" + today)));
        }
    }

    private void notifyYearGap(Plan plan, StatsService.PlanContext ctx, Task task, TaskYear taskYear,
                               LocalDate today, Counter counter) {
        Set<Long> requested = new LinkedHashSet<>();
        requested.add(task.getOwnerId());
        requested.addAll(taskStaffService.staffIds(task.getId()));
        requested.remove(null);
        List<Long> recipients = activeUserIds(requested);
        String content = progressContent(plan, ctx, task, taskYear,
                "当年进度未达标，请及时继续填报并完善认定完成值。");
        for (Long recipient : recipients) {
            counter.add(notificationService.insertOnce(notification(recipient, "WARNING",
                    "年度进度未达标：" + task.getCode(), content, "task_year", taskYear.getId(),
                    taskLink(taskYear),
                    "BOT:YEAR_GAP:" + taskYear.getId() + ":" + recipient + ":" + today)));
        }
    }

    private String progressContent(Plan plan, StatsService.PlanContext ctx, Task task, TaskYear taskYear,
                                   String action) {
        String indicatorName = ctx.indicators().get(taskYear.getIndicatorId()) == null ? "指标"
                : ctx.indicators().get(taskYear.getIndicatorId()).getName();
        return "【" + plan.getName() + "】" + indicatorName + "\n任务：" + task.getCode()
                + "\n年度：" + taskYear.getYear()
                + "\n年度目标：" + decimal(taskYear.getTarget())
                + "\n认定完成值：" + decimal(taskYear.getActualValue())
                + "\n完成率：" + percent(taskYear.getCompletionRate()) + "\n" + action;
    }

    private List<Submission> leaderReviewTodos(LoginUser user) {
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<Submission>()
                .eq(Submission::getStatus, "PENDING_REVIEW")
                .eq(Submission::getCurrentReviewNode, "LEADER_REVIEW")
                .eq(Submission::getDeleted, 0L)
                .orderByAsc(Submission::getSubmittedAt);
        reviewService.applyActionableScope(wrapper, user);
        List<Submission> submissions = submissionMapper.selectList(wrapper);
        if (submissions.isEmpty()) {
            return List.of();
        }
        Map<Long, Task> tasks = tasksOf(submissions.stream().map(Submission::getTaskId).toList());
        return submissions.stream().filter(s -> {
            Task task = tasks.get(s.getTaskId());
            return task != null && !Boolean.TRUE.equals(task.getIsHistorical());
        }).toList();
    }

    private String reviewDigestContent(List<Submission> reviews) {
        Map<Long, Task> tasks = tasksOf(reviews.stream().map(Submission::getTaskId).toList());
        List<String> lines = new ArrayList<>();
        lines.add("您有 " + reviews.size() + " 项填报待校级终审：");
        reviews.stream().limit(DIGEST_PREVIEW_SIZE).forEach(s -> {
            Task task = tasks.get(s.getTaskId());
            lines.add("· " + (task == null ? "任务" : task.getCode()) + " / " + s.getYear() + " 年度");
        });
        appendMore(lines, reviews.size());
        return String.join("\n", lines);
    }

    private String adjustmentDigestContent(List<Adjustment> adjustments) {
        Map<Long, Task> tasks = tasksOf(adjustments.stream().map(Adjustment::getTaskId).toList());
        List<String> lines = new ArrayList<>();
        lines.add("您有 " + adjustments.size() + " 项调整申请待校级审批：");
        adjustments.stream().limit(DIGEST_PREVIEW_SIZE).forEach(a -> {
            Task task = tasks.get(a.getTaskId());
            lines.add("· " + AdjustmentService.typeLabel(a.getType()) + " / "
                    + (task == null ? "指标目标调整" : task.getCode()));
        });
        appendMore(lines, adjustments.size());
        return String.join("\n", lines);
    }

    private void appendMore(List<String> lines, int total) {
        if (total > DIGEST_PREVIEW_SIZE) {
            lines.add("……另有 " + (total - DIGEST_PREVIEW_SIZE) + " 项，请进入平台处理。");
        }
    }

    private Map<Long, Task> tasksOf(Collection<Long> ids) {
        List<Long> taskIds = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .in(Task::getId, taskIds).eq(Task::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Task::getId, Function.identity()));
    }

    private List<SysUser> activeUsersWithRole(String role) {
        List<Long> ids = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRole, role).select(UserRole::getUserId))
                .stream().map(UserRole::getUserId).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getId, ids).eq(SysUser::getIsEnabled, true).eq(SysUser::getDeleted, 0L));
    }

    private List<Long> activeUserIds(Collection<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, distinct).eq(SysUser::getIsEnabled, true)
                        .eq(SysUser::getDeleted, 0L).select(SysUser::getId))
                .stream().map(SysUser::getId).toList();
    }

    private Map<Long, List<String>> rolesOf(Collection<Long> userIds) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .in(UserRole::getUserId, userIds).select(UserRole::getUserId, UserRole::getRole))
                .stream().collect(Collectors.groupingBy(UserRole::getUserId,
                        Collectors.mapping(UserRole::getRole, Collectors.toList())));
    }

    private Notification notification(Long userId, String type, String title, String content,
                                      String bizType, Long bizId, String link, String dedupKey) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setBizType(bizType);
        notification.setBizId(bizId);
        notification.setLink(link);
        notification.setDedupKey(dedupKey);
        return notification;
    }

    private String taskLink(TaskYear taskYear) {
        return "/tasks/" + taskYear.getTaskId() + "/submit?year=" + taskYear.getYear();
    }

    private boolean hasRecognizedValue(TaskYear taskYear) {
        return taskYear.getActualValue() != null || taskYear.getActualTotal() != null
                || taskYear.getActualNumerator() != null || taskYear.getActualDenominator() != null;
    }

    private String decimal(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private String percent(BigDecimal value) {
        return value == null ? "—" : decimal(value.multiply(BigDecimal.valueOf(100))) + "%";
    }

    private static final class Counter {
        private int scanned;
        private int created;
        private int deduplicated;

        private void add(boolean inserted) {
            if (inserted) {
                created++;
            } else {
                deduplicated++;
            }
        }

        private RunResult result() {
            return new RunResult(scanned, created, deduplicated);
        }
    }
}
