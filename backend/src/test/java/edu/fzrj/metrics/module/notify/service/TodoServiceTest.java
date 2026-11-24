package edu.fzrj.metrics.module.notify.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentService;
import edu.fzrj.metrics.module.notify.dto.NotifyDtos;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.ReviewService;
import edu.fzrj.metrics.module.system.service.SettingService;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskReadScopeService;
import edu.fzrj.metrics.module.training.service.TrainingReviewService;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private PlanYearMapper planYearMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private ReviewService reviewService;
    @Mock
    private AdjustmentService adjustmentService;
    @Mock
    private SettingService settingService;
    @Mock
    private TaskReadScopeService taskReadScopeService;
    @Mock
    private TrainingReviewService trainingReviewService;

    @InjectMocks
    private TodoService todoService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Submission.class);
    }

    @BeforeEach
    void login() {
        LoginUser user = new LoginUser(99L, "c01", "学院负责人", 41L,
                List.of("UNIT_OWNER"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 按计划与年度过滤后条目和汇总使用同一结果集() {
        Task first = task(101L, 11L, "A01-1-C01");
        Task second = task(102L, 11L, "A02-1-C01");
        Task otherPlan = task(103L, 22L, "A01-1-C01");
        when(taskMapper.selectList(any()))
                .thenReturn(List.of(first, second, otherPlan))
                .thenReturn(List.of());
        when(taskYearMapper.selectList(any())).thenReturn(List.of(
                taskYear(1001L, 101L, 11L, 2026, "IN_PROGRESS"),
                taskYear(1002L, 102L, 11L, 2027, "IN_PROGRESS"),
                taskYear(1003L, 103L, 22L, 2026, "IN_PROGRESS")));
        when(planYearMapper.selectList(any())).thenReturn(List.of());
        when(adjustmentService.todoAdjustments(any())).thenReturn(List.of());

        NotifyDtos.TodoView result = todoService.todos(11L, 2026);

        assertEquals(1, result.items().size());
        NotifyDtos.TodoItem item = result.items().get(0);
        assertEquals("FILL", item.type());
        assertEquals(101L, item.bizId());
        assertEquals(11L, item.planId());
        assertEquals(2026, item.year());
        assertEquals(Map.of("FILL", 1L), result.summary());
    }

    @Test
    void 未接收任务不生成ACCEPT且保留填报待办() {
        Task task = task(101L, 11L, "A01-1-C01");
        task.setStatus("DISTRIBUTED");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(
                taskYear(1001L, 101L, 11L, 2026, "NOT_ACTIVATED"),
                taskYear(1002L, 101L, 11L, 2027, "IN_PROGRESS")));
        when(adjustmentService.todoAdjustments(any())).thenReturn(List.of());

        NotifyDtos.TodoView result = todoService.todos();

        assertEquals(1, result.items().size());
        NotifyDtos.TodoItem item = result.items().get(0);
        assertEquals("FILL", item.type());
        assertEquals(101L, item.bizId());
        assertEquals(11L, item.planId());
        assertEquals(2027, item.year());
        assertEquals(Map.of("FILL", 1L), result.summary());
    }

    private Task task(Long id, Long planId, String code) {
        Task task = new Task();
        task.setId(id);
        task.setPlanId(planId);
        task.setUnitId(41L);
        task.setCode(code);
        task.setStatus("IN_PROGRESS");
        task.setIsHistorical(false);
        return task;
    }

    @Test
    void 多学院负责人同时获得两个学院的填报待办() {
        LoginUser user = new LoginUser(99L, "multi", "吴昉", 41L,
                List.of("UNIT_OWNER"), List.of(), List.of(41L, 42L));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        Task first = task(101L, 11L, "A01-C01");
        Task second = task(102L, 11L, "A01-C02");
        second.setUnitId(42L);
        int currentYear = java.time.Year.now().getValue();
        when(taskMapper.selectList(any())).thenReturn(List.of(first, second));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(
                taskYear(1001L, 101L, 11L, currentYear, "IN_PROGRESS"),
                taskYear(1002L, 102L, 11L, currentYear, "IN_PROGRESS")));
        when(adjustmentService.todoAdjustments(any())).thenReturn(List.of());

        NotifyDtos.TodoView result = todoService.todos();

        assertEquals(2, result.items().size());
        assertEquals(Map.of("FILL", 2L), result.summary());
    }

    @Test
    void 纯干事无分配任务时待办为空() {
        LoginUser user = new LoginUser(99L, "staff", "干事", 41L,
                List.of("STAFF"), List.of("submission:review"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(taskReadScopeService.staffTaskIds(user)).thenReturn(java.util.Set.of());

        NotifyDtos.TodoView result = todoService.todos();

        assertEquals(0, result.items().size());
        assertEquals(Map.of(), result.summary());
        verify(submissionMapper, never()).selectList(any());
        verify(reviewService, never()).applyActionableScope(any(), any());
    }

    @Test
    void 干事仅获得分配任务填报待办且无审核待办() {
        LoginUser user = new LoginUser(99L, "staff", "干事", 41L,
                List.of("STAFF"), List.of("submission:review"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(taskReadScopeService.staffTaskIds(user)).thenReturn(java.util.Set.of(101L));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(101L, 11L, "A01-01")));
        int currentYear = java.time.Year.now().getValue();
        when(taskYearMapper.selectList(any())).thenReturn(List.of(
                taskYear(1001L, 101L, 11L, currentYear, "IN_PROGRESS")));

        NotifyDtos.TodoView result = todoService.todos();

        assertEquals(1, result.items().size());
        assertEquals("FILL", result.items().get(0).type());
        assertEquals(101L, result.items().get(0).bizId());
        assertEquals(Map.of("FILL", 1L), result.summary());
    }

    @Test
    void 学院负责人审核待办复用可处理范围并显示院审节点() {
        LoginUser user = reviewLogin(List.of("UNIT_OWNER"));
        when(submissionMapper.selectList(any())).thenReturn(List.of(reviewSubmission("UNIT_REVIEW")));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(101L, 11L, "A01-01")));
        when(adjustmentService.todoAdjustments(any())).thenReturn(List.of());

        NotifyDtos.TodoView result = todoService.todos();

        assertEquals(1, result.items().size());
        assertEquals("2026 年填报待学院负责人审核", result.items().get(0).detail());
        assertEquals(Map.of("REVIEW", 1L), result.summary());
        LambdaQueryWrapper<Submission> wrapper = capturedReviewQuery(user);
        assertTrue(wrapper.getSqlSegment().contains("status"));
        assertTrue(wrapper.getSqlSegment().contains("deleted"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("PENDING_REVIEW"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(0L));
    }

    @Test
    void 干事兼任校级负责人时仍获得并集校审待办() {
        LoginUser user = reviewLogin(List.of("STAFF", "LEADER"));
        when(taskReadScopeService.staffTaskIds(user)).thenReturn(java.util.Set.of());
        when(submissionMapper.selectList(any())).thenReturn(List.of(reviewSubmission("LEADER_REVIEW")));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(101L, 11L, "A01-01")));
        when(adjustmentService.todoAdjustments(any())).thenReturn(List.of());

        NotifyDtos.TodoView result = todoService.todos();

        assertEquals(1, result.items().size());
        assertEquals("2026 年填报待校级负责人审核", result.items().get(0).detail());
        capturedReviewQuery(user);
    }

    private LoginUser reviewLogin(List<String> roles) {
        LoginUser user = new LoginUser(99L, "reviewer", "审核人", null,
                roles, List.of("submission:review"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user;
    }

    private Submission reviewSubmission(String reviewNode) {
        Submission submission = new Submission();
        submission.setId(501L);
        submission.setTaskId(101L);
        submission.setPlanId(11L);
        submission.setYear(2026);
        submission.setStatus("PENDING_REVIEW");
        submission.setCurrentReviewNode(reviewNode);
        submission.setDeleted(0L);
        return submission;
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Submission> capturedReviewQuery(LoginUser user) {
        ArgumentCaptor<LambdaQueryWrapper<Submission>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(submissionMapper).selectList(captor.capture());
        verify(reviewService).applyActionableScope(captor.getValue(), user);
        return captor.getValue();
    }

    private TaskYear taskYear(Long id, Long taskId, Long planId, Integer year, String status) {
        TaskYear taskYear = new TaskYear();
        taskYear.setId(id);
        taskYear.setTaskId(taskId);
        taskYear.setPlanId(planId);
        taskYear.setYear(year);
        taskYear.setYearStatus(status);
        return taskYear;
    }
}
