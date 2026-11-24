package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.module.adjustment.entity.Adjustment;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentService;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.dto.FeishuDtos;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.notify.service.NotificationService;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.stats.service.StatsService;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeishuBotServiceTest {

    @Mock private FeishuConfig config;
    @Mock private PlanMapper planMapper;
    @Mock private StatsService statsService;
    @Mock private NotificationService notificationService;
    @Mock private AdjustmentService adjustmentService;
    @Mock private TaskStaffService taskStaffService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private UserUnitService userUnitService;
    @Mock private SubmissionMapper submissionMapper;
    @Mock private ReviewService reviewService;
    @Mock private TaskMapper taskMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private FeishuPushService feishuPushService;
    @Mock private SettingService settingService;
    @InjectMocks private FeishuBotService service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Plan.class);
        TableInfoHelper.initTableInfo(assistant, Notification.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, UserRole.class);
        TableInfoHelper.initTableInfo(assistant, Submission.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
    }

    @Test
    void 数据库停用优先于环境变量且两个定时任务均不扫描() {
        when(config.isBotEnabled()).thenReturn(true);
        when(settingService.getValue(FeishuBotService.BOT_SETTING_KEY, "true")).thenReturn("false");

        assertFalse(service.isBotEnabled());
        service.scheduledProgressReminders();
        service.scheduledLeaderDailyDigest();

        verifyNoInteractions(planMapper, statsService, notificationService, adjustmentService,
                sysUserMapper, submissionMapper, taskMapper, notificationMapper);
    }

    @Test
    void 数据库启用优先于环境变量且状态返回有效开关() {
        when(config.isBotEnabled()).thenReturn(false);
        when(settingService.getValue(FeishuBotService.BOT_SETTING_KEY, "false")).thenReturn("true");

        assertTrue(service.isBotEnabled());
        assertTrue(service.status().botEnabled());
    }

    @Test
    void 推送日志只查询清空时点之后的记录() {
        LocalDateTime clearedAt = LocalDateTime.of(2026, 11, 24, 12, 30);
        when(settingService.getValue(FeishuBotService.LOG_CLEARED_AT_SETTING_KEY, ""))
                .thenReturn("2026-11-24 12:30:00");
        Page<Notification> emptyPage = Page.of(1, 20);
        when(notificationMapper.selectPage(any(), any())).thenReturn(emptyPage);

        service.logs(new FeishuDtos.BotLogQuery());

        ArgumentCaptor<LambdaQueryWrapper<Notification>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(notificationMapper).selectPage(any(), captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("created_at >"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(clearedAt));
    }

    @Test
    void 状态规则明确下发不提醒负责人且仅新增分配提醒干事() {
        FeishuDtos.BotStatusView status = service.status();

        assertEquals(6, status.rules().size());
        assertEquals("TASK_STAFF_ASSIGNED", status.rules().get(0).code());
        assertEquals("干事任务分配", status.rules().get(0).name());
        assertEquals("学院任务下发不通知学院负责人；仅新增任务分配时通知对应干事",
                status.rules().get(0).description());
    }

    @Test
    void 滞后提醒覆盖全部学院负责人并在同日去重且排除历史任务() {
        LocalDate today = LocalDate.of(2026, 11, 24);
        Plan plan = plan();
        Task current = task(101L, "A01-C01", false);
        Task historical = task(102L, "A01-C02", true);
        TaskYear currentYear = taskYear(1001L, 101L, "IN_PROGRESS", "0.1");
        TaskYear historicalYear = taskYear(1002L, 102L, "IN_PROGRESS", "0.1");
        stubProgress(plan, context(plan, List.of(current, historical), List.of(currentYear, historicalYear)));
        when(adjustmentService.unitOwnerIds(41L)).thenReturn(List.of(201L, 202L));
        when(notificationService.insertOnce(any())).thenReturn(true, true, false, false);

        FeishuBotService.RunResult first = service.runProgressAt(today);
        FeishuBotService.RunResult second = service.runProgressAt(today);

        assertEquals(new FeishuBotService.RunResult(1, 2, 0), first);
        assertEquals(new FeishuBotService.RunResult(1, 0, 2), second);
        List<Notification> notifications = capturedNotifications(4);
        assertEquals(List.of(201L, 202L, 201L, 202L),
                notifications.stream().map(Notification::getUserId).toList());
        assertEquals(List.of(
                        "BOT:LAG:1001:201:2026-11-24", "BOT:LAG:1001:202:2026-11-24",
                        "BOT:LAG:1001:201:2026-11-24", "BOT:LAG:1001:202:2026-11-24"),
                notifications.stream().map(Notification::getDedupKey).toList());
        verify(adjustmentService, times(2)).unitOwnerIds(41L);
        verify(taskStaffService, never()).staffIds(any());
    }

    @Test
    void 年度未达标仅通知任务负责人和仍启用的当前干事() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        Plan plan = plan();
        Task task = task(101L, "A01-C01", false);
        task.setOwnerId(201L);
        TaskYear taskYear = taskYear(1001L, 101L, "IN_PROGRESS", "0.5");
        taskYear.setTarget(new BigDecimal("10"));
        taskYear.setActualValue(new BigDecimal("5"));
        taskYear.setIsAchieved(false);
        stubProgress(plan, context(plan, List.of(task), List.of(taskYear)));
        when(taskStaffService.staffIds(101L)).thenReturn(List.of(202L, 203L));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user(201L), user(202L)));
        when(notificationService.insertOnce(any())).thenReturn(true);

        FeishuBotService.RunResult result = service.runProgressAt(today);

        assertEquals(new FeishuBotService.RunResult(1, 2, 0), result);
        List<Notification> notifications = capturedNotifications(2);
        assertEquals(List.of(201L, 202L), notifications.stream().map(Notification::getUserId).toList());
        assertTrue(notifications.stream().allMatch(n -> n.getTitle().startsWith("年度进度未达标")));
        LambdaQueryWrapper<SysUser> activeQuery = capturedActiveUserQuery();
        assertTrue(activeQuery.getSqlSegment().contains("is_enabled"));
        assertTrue(activeQuery.getSqlSegment().contains("deleted"));
        assertTrue(activeQuery.getParamNameValuePairs().containsValue(true));
        assertTrue(activeQuery.getParamNameValuePairs().containsValue(0L));
        verify(taskStaffService).staffIds(101L);
        verify(adjustmentService, never()).unitOwnerIds(any());
    }

    @Test
    void 每位启用校级负责人分别收到审核与校审节点调整汇总() {
        LocalDate today = LocalDate.of(2026, 11, 24);
        SysUser firstLeader = user(1L);
        SysUser secondLeader = user(2L);
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(role(1L), role(2L), role(3L)))
                .thenReturn(List.of());
        when(sysUserMapper.selectList(any())).thenReturn(List.of(firstLeader, secondLeader));
        when(userUnitService.unitIdsOf(anyCollection())).thenReturn(Map.of());
        Task firstTask = task(101L, "A01-C01", false);
        Task secondTask = task(102L, "A01-C02", false);
        when(taskMapper.selectList(any())).thenReturn(List.of(firstTask, secondTask));
        when(submissionMapper.selectList(any()))
                .thenReturn(List.of(submission(501L, 101L)))
                .thenReturn(List.of(submission(502L, 102L)));
        when(adjustmentService.todoAdjustments(any()))
                .thenReturn(List.of(adjustment(601L, 101L, "LEADER_APPROVAL"),
                        adjustment(611L, 101L, "OWNER_REVIEW")))
                .thenReturn(List.of(adjustment(602L, 102L, "LEADER_APPROVAL"),
                        adjustment(612L, 102L, "RECEIVER_CONFIRM")));
        when(notificationService.insertOnce(any())).thenReturn(true);

        FeishuBotService.RunResult result = service.runLeaderDigestAt(today);

        assertEquals(new FeishuBotService.RunResult(4, 4, 0), result);
        List<Notification> notifications = capturedNotifications(4);
        for (Long leaderId : List.of(1L, 2L)) {
            List<Notification> own = notifications.stream().filter(n -> leaderId.equals(n.getUserId())).toList();
            assertEquals(Set.of("REVIEW_TODO", "ADJUSTMENT_TODO"),
                    own.stream().map(Notification::getType).collect(Collectors.toSet()));
            assertTrue(own.stream().anyMatch(n -> ("BOT:REVIEW_DAILY:" + leaderId + ":" + today).equals(n.getDedupKey())));
            assertTrue(own.stream().anyMatch(n -> ("BOT:ADJUSTMENT_DAILY:" + leaderId + ":" + today).equals(n.getDedupKey())));
            assertTrue(own.stream().allMatch(n -> !n.getTitle().endsWith("2 项")));
        }
        ArgumentCaptor<LoginUser> users = ArgumentCaptor.forClass(LoginUser.class);
        verify(adjustmentService, times(2)).todoAdjustments(users.capture());
        assertEquals(List.of(1L, 2L), users.getAllValues().stream().map(LoginUser::getUserId).toList());
    }

    @Test
    void 校级负责人无待办时不生成汇总通知() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role(1L))).thenReturn(List.of());
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user(1L)));
        when(userUnitService.unitIdsOf(anyCollection())).thenReturn(Map.of());
        when(submissionMapper.selectList(any())).thenReturn(List.of());
        when(adjustmentService.todoAdjustments(any())).thenReturn(List.of());

        FeishuBotService.RunResult result = service.runLeaderDigestAt(LocalDate.of(2026, 11, 24));

        assertEquals(new FeishuBotService.RunResult(0, 0, 0), result);
        verify(notificationService, never()).insertOnce(any());
        verify(taskMapper, never()).selectList(any());
    }

    private void stubProgress(Plan plan, StatsService.PlanContext context) {
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(statsService.lagThreshold()).thenReturn(new BigDecimal("0.5"));
        when(statsService.loadContext(eq(plan), eq(2026), any())).thenReturn(context);
    }

    private List<Notification> capturedNotifications(int count) {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService, times(count)).insertOnce(captor.capture());
        return captor.getAllValues();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<SysUser> capturedActiveUserQuery() {
        ArgumentCaptor<LambdaQueryWrapper<SysUser>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sysUserMapper).selectList(captor.capture());
        return captor.getValue();
    }

    private static StatsService.PlanContext context(Plan plan, List<Task> tasks, List<TaskYear> taskYears) {
        Indicator indicator = new Indicator();
        indicator.setId(900L);
        indicator.setName("测试指标");
        PlanYear planYear = new PlanYear();
        planYear.setYear(2026);
        planYear.setStatus("ACTIVE");
        return new StatsService.PlanContext(plan, Map.of(900L, indicator), Map.of(), Map.of(),
                tasks.stream().collect(Collectors.toMap(Task::getId, Function.identity())), taskYears,
                Map.of(), Map.of(), Map.of(2026, planYear));
    }

    private static Plan plan() {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setName("测试计划");
        return plan;
    }

    private static Task task(Long id, String code, boolean historical) {
        Task task = new Task();
        task.setId(id);
        task.setCode(code);
        task.setUnitId(41L);
        task.setStatus("IN_PROGRESS");
        task.setIsHistorical(historical);
        return task;
    }

    private static TaskYear taskYear(Long id, Long taskId, String status, String completionRate) {
        TaskYear taskYear = new TaskYear();
        taskYear.setId(id);
        taskYear.setTaskId(taskId);
        taskYear.setIndicatorId(900L);
        taskYear.setYear(2026);
        taskYear.setTarget(new BigDecimal("100"));
        taskYear.setYearStatus(status);
        taskYear.setCompletionRate(new BigDecimal(completionRate));
        return taskYear;
    }

    private static SysUser user(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("leader" + id);
        user.setName("校级负责人" + id);
        user.setIsEnabled(true);
        user.setDeleted(0L);
        return user;
    }

    private static UserRole role(Long userId) {
        UserRole role = new UserRole();
        role.setUserId(userId);
        role.setRole("LEADER");
        return role;
    }

    private static Submission submission(Long id, Long taskId) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setTaskId(taskId);
        submission.setYear(2026);
        return submission;
    }

    private static Adjustment adjustment(Long id, Long taskId, String currentNode) {
        Adjustment adjustment = new Adjustment();
        adjustment.setId(id);
        adjustment.setTaskId(taskId);
        adjustment.setType("POSTPONE");
        adjustment.setCurrentNode(currentNode);
        return adjustment;
    }
}
